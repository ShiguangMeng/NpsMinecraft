import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Offline, Gradle-free remapper built on ASM.
 *
 * Mapping sources (local or small downloads):
 *   proguard  Mojang client.txt  -- lines are "officialName -> obfName" (note the direction)
 *   tiny      Fabric intermediary  -- "CLASS/METHOD/FIELD obfName ... targetName", obf descriptors
 *   tsrg      MCP joined.tsrg2     -- "obfName obfDesc srgName"; class column is MCP-only, SRG keeps Mojang classes
 *
 * Modes
 *   mojmap <in.jar> <out.jar> <proguard.txt>
 *       obf jar -> Mojang-official jar, used as a compile classpath
 *   remap  <inDir> <outDir> <proguard.txt> inter <intermediary.jar>
 *   remap  <inDir> <outDir> <proguard.txt> srg   <mcp_config.zip|joined.tsrg>
 *       Mojang-official classes -> target namespace, ready to ship
 */
public final class RemapTool {

    /** moj internal name -> obf internal name */
    static final Map<String, String> mojToObfClass = new HashMap<>(1 << 13);
    /** obf internal name -> moj internal name */
    static final Map<String, String> obfToMojClass = new HashMap<>(1 << 13);
    /** obf class -> target class (intermediary only; SRG keeps Mojang class names) */
    static final Map<String, String> obfToTgtClass = new HashMap<>(1 << 13);

    /** obfOwner "|" obfName "|" obfDesc -> mojName */
    static final Map<String, String> obfToMojMethod = new HashMap<>(1 << 17);
    /** obfOwner "|" obfName -> mojName */
    static final Map<String, String> obfToMojField = new HashMap<>(1 << 15);
    /** obfOwner "|" obfName "|" obfDesc -> targetName */
    static final Map<String, String> obfToTgtMethod = new HashMap<>(1 << 17);
    /** obfOwner "|" obfName -> targetName */
    static final Map<String, String> obfToTgtField = new HashMap<>(1 << 15);

    /** mojOwner "|" mojName "|" mojDesc -> targetName */
    static final Map<String, String> fwdMethod = new HashMap<>(1 << 17);
    /** mojOwner "|" mojName -> targetName */
    static final Map<String, String> fwdField = new HashMap<>(1 << 15);
    /**
     * mojName "|" mojDesc -> targetName, inheritance-tolerant fallback.
     *
     * <p>Only consulted for game classes, and only after the exact-owner key AND the
     * supertype walk both miss. It is inherently ambiguous: several unrelated Minecraft
     * classes declare same-named methods with identical descriptors yet different SRG
     * names, and whichever entry was written last wins here. That ambiguity is exactly
     * how {@code ClientLevel.getBlockState} once got renamed to an unrelated
     * {@code m_191828_} instead of the inherited {@code m_8055_}.
     */
    static final Map<String, String> fwdMethodByName = new HashMap<>(1 << 17);
    static final Map<String, String> fwdFieldByName = new HashMap<>(1 << 15);

    /** mojClassName -> superClassName, used to resolve inherited members. */
    static final Map<String, String> mojSuper = new HashMap<>(1 << 13);
    /** mojClassName -> direct interface names. */
    static final Map<String, List<String>> mojInterfaces = new HashMap<>(1 << 13);

    static int nClass, nMethod, nField;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            usage();
            System.exit(2);
        }
        long t0 = System.nanoTime();
        switch (args[0]) {
            case "mojmap" -> {
                loadProguard(Paths.get(args[3]));
                remapJar(Paths.get(args[1]), Paths.get(args[2]), new ObfToMoj());
            }
            case "remap" -> {
                if (args.length < 6) { usage(); System.exit(2); }
                String target = args[4];
                loadProguard(Paths.get(args[3]));
                switch (target) {
                    case "inter" -> loadTiny(Paths.get(args[5]));
                    case "srg" -> loadTsrg(Paths.get(args[5]));
                    default -> { System.err.println("unknown target: " + target); System.exit(2); }
                }
                // 7th arg (optional): the Mojang-official client jar, used only to learn supertypes.
                // Without it, inherited members fall back to the ambiguous by-name table.
                if (args.length >= 7 && !args[6].isBlank() && Files.exists(Paths.get(args[6]))) {
                    loadHierarchy(Paths.get(args[6]));
                }
                buildForwardTables();
                remapDir(Paths.get(args[1]), Paths.get(args[2]), new MojToTgt());
            }
            case "index" -> {
                // Emit a compact index of Minecraft in the TARGET namespace, derived from the
                // mapping files plus the mojmap client jar's class headers. Used by LinkCheck.
                //   index <out.txt> <proguard> <inter|srg> <mapfile> <mojClientJar>
                if (args.length < 6) { usage(); System.exit(2); }
                loadProguard(Paths.get(args[2]));
                String target = args[3];
                switch (target) {
                    case "inter" -> loadTiny(Paths.get(args[4]));
                    case "srg" -> loadTsrg(Paths.get(args[4]));
                    default -> { System.err.println("unknown target: " + target); System.exit(2); }
                }
                loadHierarchy(Paths.get(args[5]));
                buildForwardTables();
                writeIndex(Paths.get(args[1]), Paths.get(args[5]));
            }
            case "inter2moj" -> {
                // intermediary-named jar (e.g. a Fabric API module) -> Mojang-official names,
                // so it can sit on the same compile classpath as our mojmap MC jar.
                loadProguard(Paths.get(args[3]));
                loadTiny(Paths.get(args[4]));
                buildBackwardTables();
                remapJar(Paths.get(args[1]), Paths.get(args[2]), new TgtToMoj());
            }
            default -> { usage(); System.exit(2); }
        }
        System.out.printf("[RemapTool] %s ok in %d ms | classes=%d methods=%d fields=%d%n",
                args[0], (System.nanoTime() - t0) / 1_000_000L, nClass, nMethod, nField);
    }

    /**
     * Write a flat member index of Minecraft in the target namespace.
     *
     * <p>Format, one record per line (tab separated):
     * <pre>
     * C  &lt;targetClass&gt;  &lt;targetSuper&gt;  &lt;targetIfacesCommaSeparated&gt;
     * M  &lt;targetClass&gt;  &lt;targetMethodName&gt;
     * F  &lt;targetClass&gt;  &lt;targetFieldName&gt;
     * </pre>
     *
     * <p>Members are emitted on the class whose Mojang side declared them, so a consumer that
     * walks the supertype chain finds inherited members where the JVM would. Descriptors are
     * intentionally omitted: LinkCheck resolves by name, and dropping them keeps the index
     * small enough to generate per matrix row.
     */
    static void writeIndex(Path out, Path mojJar) throws IOException {
        MojToTgt mapper = new MojToTgt();
        Map<String, Set<String>> methodsByOwner = new HashMap<>(1 << 13);
        Map<String, Set<String>> fieldsByOwner = new HashMap<>(1 << 13);

        for (Map.Entry<String, String> e : fwdMethod.entrySet()) {
            String key = e.getKey();
            int p1 = key.indexOf('|');
            String owner = key.substring(0, p1);
            methodsByOwner.computeIfAbsent(mapper.map(owner), k -> new TreeSet<>()).add(e.getValue());
        }
        for (Map.Entry<String, String> e : fwdField.entrySet()) {
            String key = e.getKey();
            int p1 = key.lastIndexOf('|');
            String owner = key.substring(0, p1);
            fieldsByOwner.computeIfAbsent(mapper.map(owner), k -> new TreeSet<>()).add(e.getValue());
        }

        Map<String, String> classSuper = new HashMap<>(1 << 13);
        Map<String, List<String>> classIfaces = new HashMap<>(1 << 13);
        List<String> order = new ArrayList<>();
        try (ZipFile zf = new ZipFile(mojJar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry ze = en.nextElement();
                if (!ze.getName().endsWith(".class")) continue;
                String internal = ze.getName().substring(0, ze.getName().length() - 6);
                if (!isGameClass(internal)) continue;
                try (InputStream in = zf.getInputStream(ze)) {
                    ClassReader cr = new ClassReader(in.readAllBytes());
                    String t = mapper.map(internal);
                    order.add(t);
                    classSuper.put(t, cr.getSuperName() == null ? "" : mapper.map(cr.getSuperName()));
                    String[] itfs = cr.getInterfaces();
                    List<String> mappedItfs = new ArrayList<>();
                    if (itfs != null) for (String itf : itfs) mappedItfs.add(mapper.map(itf));
                    classIfaces.put(t, mappedItfs);

                    // Members declared in the jar but absent from the mapping files keep their
                    // Mojang name in the target namespace, so record them as-is.
                    HeadIndexer idx = new HeadIndexer();
                    cr.accept(idx, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    Set<String> ms = methodsByOwner.computeIfAbsent(t, k -> new TreeSet<>());
                    ms.addAll(idx.accountedMethods);
                    for (String head : idx.methodHeads) {
                        int sp = head.indexOf(' ');
                        String mojName = head.substring(0, sp);
                        String mojDesc = head.substring(sp + 1);
                        String mapped = fwdMethod.get(internal + "|" + mojName + "|" + mojDesc);
                        ms.add(mapped != null ? mapped : mojName);
                    }
                    Set<String> fs = fieldsByOwner.computeIfAbsent(t, k -> new TreeSet<>());
                    fs.addAll(idx.accountedFields);
                    for (String head : idx.fieldHeads) {
                        String mojName = head.substring(0, head.indexOf(' '));
                        String mapped = fwdField.get(internal + "|" + mojName);
                        fs.add(mapped != null ? mapped : mojName);
                    }
                } catch (RuntimeException ignored) {
                    // an unreadable class in the MC jar is not fatal for the index
                }
            }
        }

        StringBuilder sb = new StringBuilder(1 << 22);
        for (String t : order) {
            String sup = classSuper.getOrDefault(t, "");
            List<String> itfs = classIfaces.getOrDefault(t, List.of());
            sb.append("C\t").append(t).append('\t').append(sup).append('\t')
              .append(String.join(",", itfs)).append('\n');
            for (String m : methodsByOwner.getOrDefault(t, Collections.<String>emptySet())) {
                sb.append("M\t").append(t).append('\t').append(m).append('\n');
            }
            for (String f : fieldsByOwner.getOrDefault(t, Collections.<String>emptySet())) {
                sb.append("F\t").append(t).append('\t').append(f).append('\n');
            }
        }
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[RemapTool] index: " + order.size() + " classes -> " + out);
    }

    /** Class indexer that only records member heads (name + descriptor). */
    static final class HeadIndexer extends ClassVisitor {
        final List<String> methodHeads = new ArrayList<>();
        final List<String> fieldHeads = new ArrayList<>();
        final Set<String> accountedMethods = new TreeSet<>();
        final Set<String> accountedFields = new TreeSet<>();

        HeadIndexer() {
            super(Opcodes.ASM9);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            fieldHeads.add(name + " " + descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            methodHeads.add(name + " " + descriptor);
            return null;
        }
    }

    static void usage() {
        System.err.println("""
                usage:
                  RemapTool mojmap <in.jar> <out.jar> <proguard.txt>
                  RemapTool remap  <inDir> <outDir> <proguard.txt> inter <intermediary.jar> [mojClientJar]
                  RemapTool remap  <inDir> <outDir> <proguard.txt> srg   <mcp_config.zip|joined.tsrg> [mojClientJar]
                  RemapTool index  <out.txt> <proguard.txt> <inter|srg> <map> <mojClientJar>
                """);
    }

    /* ============================ readers ============================ */

    /**
     * Mojang proguard mapping. Direction is official -> obfuscated:
     *   "com.mojang.blaze3d.Blaze3D -> ega:"
     *   "    12:13:void process(com.mojang.blaze3d.pipeline.RenderPipeline,float) -> a"
     *   "    org.slf4j.Logger LOGGER -> b"
     */
    static void loadProguard(Path file) throws IOException {
        String curObfClass = null;
        String curMojClass = null;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        // Pass 1: collect every class mapping first. Member descriptors reference classes that
        // may be declared later in the file, so translating them during a single pass would
        // leave those references in their official names and break the join against tiny/tsrg.
        for (String raw : lines) {
            if (raw.isEmpty() || raw.charAt(0) == '#') continue;
            if (Character.isWhitespace(raw.charAt(0))) continue;
            if (!raw.endsWith(":")) continue;
            int arrow = raw.indexOf(" -> ");
            if (arrow < 0) continue;
            String moj = raw.substring(0, arrow).trim().replace('.', '/');
            String obf = raw.substring(arrow + 4, raw.length() - 1).trim();
            obfToMojClass.put(obf, moj);
            mojToObfClass.put(moj, obf);
        }
        nClass = obfToMojClass.size();
        // Pass 2: members, now that every class name is known.
        for (String raw : lines) {
            if (raw.isEmpty() || raw.charAt(0) == '#') continue;
            if (!Character.isWhitespace(raw.charAt(0))) {
                if (!raw.endsWith(":")) continue;
                int arrow = raw.indexOf(" -> ");
                if (arrow < 0) continue;
                String moj = raw.substring(0, arrow).trim().replace('.', '/');
                String obf = raw.substring(arrow + 4, raw.length() - 1).trim();
                curMojClass = moj;
                curObfClass = obf;
                obfToMojClass.put(obf, moj);
                mojToObfClass.put(moj, obf);
                nClass++;
                continue;
            }
            if (curObfClass == null) continue;
            String line = stripLineNumbers(raw.trim());
            int arrow = line.indexOf(" -> ");
            if (arrow < 0) continue;
            String left = line.substring(0, arrow).trim();
            String obfName = line.substring(arrow + 4).trim();
            int paren = left.indexOf('(');
            if (paren >= 0) {
                int sp = left.lastIndexOf(' ', paren);
                if (sp < 0) continue;
                String mojName = left.substring(sp + 1, paren);
                String retType = left.substring(0, sp).trim();
                String argDesc = argListToDesc(left.substring(paren));
                String retDesc = typeToDesc(retType);
                if (argDesc == null || retDesc == null) continue;
                // the JVM descriptor ASM hands us always carries the return type
                String mojDesc = argDesc + retDesc;
                String obfDesc = translateDesc(mojDesc, mojToObfClass);
                obfToMojMethod.put(curObfClass + "|" + obfName + "|" + obfDesc, mojName);
                obfToMojMethod.put(curObfClass + "|" + obfName + "|" + mojDesc, mojName);
                nMethod++;
            } else {
                int sp = left.lastIndexOf(' ');
                if (sp < 0) continue;
                String mojName = left.substring(sp + 1);
                obfToMojField.put(curObfClass + "|" + obfName, mojName);
                nField++;
            }
        }
        System.out.println("[RemapTool] proguard: " + obfToMojClass.size() + " classes, "
                + obfToMojMethod.size() + " method keys, " + obfToMojField.size() + " fields");
    }

    static String stripLineNumbers(String line) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (Character.isDigit(c)) i++;
            else if (c == ':' && i > 0) i++;
            else break;
        }
        return i >= 2 && line.charAt(i - 1) == ':' ? line.substring(i) : line;
    }

    /** "(int,java.lang.String[])" -> "(ILjava/lang/String;)" */
    static String argListToDesc(String args) {
        StringBuilder sb = new StringBuilder("(");
        String body = args.substring(1, args.length() - 1).trim();
        if (!body.isEmpty()) {
            for (String p : body.split(",")) {
                String t = p.trim();
                if (t.isEmpty()) return null;
                int k = 0;
                while (k < t.length() && (Character.isDigit(t.charAt(k)) || t.charAt(k) == ':')) k++;
                if (k > 0 && k < t.length() && t.charAt(k - 1) == ':') t = t.substring(k);
                String d = typeToDesc(t);
                if (d == null) return null;
                sb.append(d);
            }
        }
        return sb.append(')').toString();
    }

    static String typeToDesc(String type) {
        type = type.trim();
        int dims = 0;
        while (type.endsWith("[]")) {
            dims++;
            type = type.substring(0, type.length() - 2).trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dims; i++) sb.append('[');
        switch (type) {
            case "void": sb.append('V'); break;
            case "boolean": sb.append('Z'); break;
            case "byte": sb.append('B'); break;
            case "char": sb.append('C'); break;
            case "short": sb.append('S'); break;
            case "int": sb.append('I'); break;
            case "long": sb.append('J'); break;
            case "float": sb.append('F'); break;
            case "double": sb.append('D'); break;
            default:
                if (type.isEmpty() || type.indexOf('.') == 0) return null;
                sb.append('L').append(type.replace('.', '/')).append(';');
        }
        return sb.toString();
    }

    /** Fabric tiny v1, entries use obfuscated descriptors. */
    static void loadTiny(Path fileOrJar) throws IOException {
        List<String> lines = readLines(fileOrJar, "mappings/mappings.tiny");
        String curObf = null;
        int n = 0;
        for (String line : lines) {
            if (line.startsWith("CLASS\t")) {
                String[] p = line.split("\t");
                if (p.length >= 3) {
                    curObf = p[1];
                    obfToTgtClass.put(p[1], p[2]);
                    n++;
                }
            } else if (line.startsWith("METHOD\t") && curObf != null) {
                String[] p = line.split("\t");
                // Fabric ships the "flat" tiny variant: METHOD <owner> <desc> <obfName> <interName>
                if (p.length >= 5) {
                    obfToTgtMethod.put(p[1] + "|" + p[3] + "|" + p[2], p[4]);
                } else if (p.length == 4) {
                    obfToTgtMethod.put(curObf + "|" + p[1] + "|" + p[2], p[3]);
                }
            } else if (line.startsWith("FIELD\t") && curObf != null) {
                String[] p = line.split("\t");
                // FIELD <owner> <desc> <obfName> <interName>
                if (p.length >= 5) {
                    obfToTgtField.put(p[1] + "|" + p[3], p[4]);
                } else if (p.length == 4) {
                    obfToTgtField.put(curObf + "|" + p[1], p[3]);
                }
            }
        }
        System.out.println("[RemapTool] intermediary: " + n + " classes, "
                + obfToTgtMethod.size() + " methods");
    }

    /** MCP tsrg2. Class column 1 is an MCP container name; SRG keeps Mojang class names. */
    static void loadTsrg(Path fileOrZip) throws IOException {
        List<String> lines = readLines(fileOrZip, "config/joined.tsrg");
        if (lines.isEmpty()) throw new IOException("empty tsrg");
        String curObf = null;
        int n = 0;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) continue;
            char c0 = line.charAt(0);
            if (c0 != '\t') {
                // class lines are SPACE separated: "byo net/minecraft/src/C_1141_ 1141"
                String[] p = line.trim().split("\\s+");
                if (p.length >= 2) {
                    curObf = p[0];
                    n++;
                    // tsrg1 (1.16.5) carries real SRG class names ("djw net/minecraft/client/settings/KeyBinding");
                    // tsrg2 carries MCP container names (net/minecraft/src/C_xxxx_) which are NOT runtime names,
                    // because for 1.17+ SRG keeps the Mojang class names unchanged.
                    if (!p[1].startsWith("net/minecraft/src/C_")) {
                        obfToTgtClass.put(p[0], p[1]);
                    }
                }
                continue;
            }
            if (curObf == null) continue;
            if (line.length() > 1 && line.charAt(1) == '\t') continue; // parameter line
            // member lines are TAB then SPACE separated: "\ta (F)Lorg/joml/Quaternionf; m_252774_ 252774"
            String[] p = line.trim().split("\\s+");
            if (p.length >= 3 && p[1].startsWith("(")) {
                // method: tsrg2 "<obfName> <obfDesc> <srgName> <id>"
                //         tsrg1 "<obfName> <obfDesc> <srgName>"  (1.16.5, no id column)
                obfToTgtMethod.put(curObf + "|" + p[0] + "|" + p[1], p[2]);
            } else if (p.length >= 2 && !p[1].startsWith("(")) {
                // field: tsrg2 "<obfName> <srgName> <id>" / tsrg1 "<obfName> <srgName>"
                obfToTgtField.put(curObf + "|" + p[0], p[1]);
            }
        }
        System.out.println("[RemapTool] tsrg(srg): " + n + " classes, "
                + obfToTgtMethod.size() + " methods, " + obfToTgtField.size() + " fields");
    }

    static List<String> readLines(Path fileOrZip, String inner) throws IOException {
        if (Files.isDirectory(fileOrZip)) return Files.readAllLines(fileOrZip, StandardCharsets.UTF_8);
        String lower = fileOrZip.toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar") || lower.endsWith(".zip")) {
            try (ZipFile zf = new ZipFile(fileOrZip.toFile())) {
                ZipEntry e = zf.getEntry(inner);
                if (e == null) {
                    for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                        ZipEntry cand = en.nextElement();
                        String nm = cand.getName();
                        if (nm.endsWith(".tiny") || nm.endsWith(".tsrg")) { e = cand; break; }
                    }
                }
                if (e == null) throw new IOException("mapping entry not found in " + fileOrZip);
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8))) {
                    List<String> out = new ArrayList<>(1 << 17);
                    String l;
                    while ((l = r.readLine()) != null) out.add(l);
                    return out;
                }
            }
        }
        return Files.readAllLines(fileOrZip, StandardCharsets.UTF_8);
    }

    /* ============================ forward tables ============================ */

    static void buildForwardTables() {
        List<String> methodKeys = new ArrayList<>(obfToMojMethod.keySet());
        for (String key : methodKeys) {
            String mojName = obfToMojMethod.get(key);
            String tgtName = obfToTgtMethod.get(key);
            if (tgtName == null || tgtName.equals(mojName)) continue;
            int p1 = key.indexOf('|');
            String obfOwner = key.substring(0, p1);
            String rest = key.substring(p1 + 1);
            int p2 = rest.indexOf('|');
            String obfDesc = rest.substring(p2 + 1);
            String mojOwner = obfToMojClass.get(obfOwner);
            if (mojOwner == null) continue;
            String mojDesc = translateDesc(obfDesc, obfToMojClass);
            fwdMethod.put(mojOwner + "|" + mojName + "|" + mojDesc, tgtName);
            fwdMethodByName.put(mojName + "|" + mojDesc, tgtName);
        }
        for (Map.Entry<String, String> e : obfToMojField.entrySet()) {
            String key = e.getKey();
            String tgtName = obfToTgtField.get(key);
            if (tgtName == null || tgtName.equals(e.getValue())) continue;
            int p1 = key.indexOf('|');
            String mojOwner = obfToMojClass.get(key.substring(0, p1));
            if (mojOwner == null) continue;
            String mojName = e.getValue();
            fwdField.put(mojOwner + "|" + mojName, tgtName);
            fwdFieldByName.put(mojName, tgtName);
        }
        System.out.println("[RemapTool] forward: " + fwdMethod.size() + " methods, "
                + fwdField.size() + " fields, by-name " + fwdMethodByName.size()
                + "/" + fwdFieldByName.size());
    }

    /* ============================ backward tables ============================ */

    /** target class -> moj class */
    static final Map<String, String> tgtToMojClass = new HashMap<>(1 << 13);
    /** tgtOwner "|" tgtName "|" tgtDesc -> mojName */
    static final Map<String, String> bwdMethod = new HashMap<>(1 << 17);
    /** tgtOwner "|" tgtName -> mojName */
    static final Map<String, String> bwdField = new HashMap<>(1 << 15);

    static void buildBackwardTables() {
        for (Map.Entry<String, String> e : obfToTgtClass.entrySet()) {
            String moj = obfToMojClass.get(e.getKey());
            if (moj != null) tgtToMojClass.put(e.getValue(), moj);
        }
        for (Map.Entry<String, String> e : obfToTgtMethod.entrySet()) {
            String key = e.getKey();
            String mojName = obfToMojMethod.get(key);
            if (mojName == null) continue;
            int p1 = key.indexOf('|');
            String obfOwner = key.substring(0, p1);
            String rest = key.substring(p1 + 1);
            int p2 = rest.indexOf('|');
            String obfDesc = rest.substring(p2 + 1);
            String tgtOwner = obfToTgtClass.get(obfOwner);
            if (tgtOwner == null) continue;
            String mojDesc = translateDesc(obfDesc, obfToMojClass);
            String tgtDesc = translateDesc(obfDesc, obfToTgtClass);
            bwdMethod.put(tgtOwner + "|" + e.getValue() + "|" + tgtDesc, mojName);
            bwdMethod.put(tgtOwner + "|" + e.getValue() + "|" + mojDesc, mojName);
        }
        for (Map.Entry<String, String> e : obfToTgtField.entrySet()) {
            String key = e.getKey();
            String mojName = obfToMojField.get(key);
            if (mojName == null) continue;
            int p1 = key.indexOf('|');
            String tgtOwner = obfToTgtClass.get(key.substring(0, p1));
            if (tgtOwner == null) continue;
            bwdField.put(tgtOwner + "|" + e.getValue(), mojName);
        }
        System.out.println("[RemapTool] backward: " + tgtToMojClass.size() + " classes, "
                + bwdMethod.size() + " methods, " + bwdField.size() + " fields");
    }

    static final class TgtToMoj extends Remapper {
        @Override public String map(String internalName) {
            return tgtToMojClass.getOrDefault(internalName, internalName);
        }
        @Override public String mapMethodName(String owner, String name, String descriptor) {
            String v = bwdMethod.get(owner + "|" + name + "|" + descriptor);
            return v != null ? v : name;
        }
        @Override public String mapFieldName(String owner, String name, String descriptor) {
            String v = bwdField.get(owner + "|" + name);
            return v != null ? v : name;
        }
    }

    static String translateDesc(String desc, Map<String, String> classMap) {
        StringBuilder sb = new StringBuilder(desc.length() + 8);
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) { sb.append(desc, i, desc.length()); break; }
                String cls = desc.substring(i + 1, end);
                sb.append('L').append(classMap.getOrDefault(cls, cls)).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /* ============================ remappers ============================ */

    static final class ObfToMoj extends Remapper {
        @Override public String map(String internalName) {
            return obfToMojClass.getOrDefault(internalName, internalName);
        }
        @Override public String mapMethodName(String owner, String name, String descriptor) {
            String v = obfToMojMethod.get(owner + "|" + name + "|" + descriptor);
            if (v != null) return v;
            // descriptors inside the jar are obfuscated; try the translated form too
            String alt = obfToMojMethod.get(owner + "|" + name + "|"
                    + translateDesc(descriptor, obfToMojClass));
            return alt != null ? alt : name;
        }
        @Override public String mapFieldName(String owner, String name, String descriptor) {
            String v = obfToMojField.get(owner + "|" + name);
            return v != null ? v : name;
        }
    }

    static final class MojToTgt extends Remapper {
        @Override public String map(String internalName) {
            return obfToTgtClass.getOrDefault(mojToObfClass.getOrDefault(internalName, internalName), internalName);
        }
        @Override public String mapMethodName(String owner, String name, String descriptor) {
            String v = fwdMethod.get(owner + "|" + name + "|" + descriptor);
            if (v != null) return v;
            // The by-name fallback must ONLY be consulted for game classes. Our own mod classes
            // (com/shiguangmeng/... / net/fabricmc/...) also declare methods called tick() and reset();
            // using the fallback there renamed stop() to Minecraft.stop() and broke the mod.
            if (!isGameClass(owner)) return name;
            // Walk the Mojang supertype chain before falling back to the ambiguous by-name table:
            // an inherited member keeps the SRG name declared by the class that actually owns it.
            v = resolveInheritedMethod(owner, name, descriptor, new HashSet<>());
            if (v != null) return v;
            v = fwdMethodByName.get(name + "|" + descriptor);
            if (v != null) {
                ambiguousMethod.add(owner + "|" + name + "|" + descriptor + " -> " + v);
                return v;
            }
            missedMethod.add(owner + "|" + name + "|" + descriptor);
            return name;
        }
        @Override public String mapFieldName(String owner, String name, String descriptor) {
            String v = fwdField.get(owner + "|" + name);
            if (v != null) return v;
            if (!isGameClass(owner)) return name;
            v = resolveInheritedField(owner, name, new HashSet<>());
            if (v != null) return v;
            v = fwdFieldByName.get(name);
            if (v != null) {
                ambiguousField.add(owner + "|" + name + " -> " + v);
                return v;
            }
            missedField.add(owner + "|" + name);
            return name;
        }
    }

    /** Depth-first search of the Mojang supertype graph for an exact member mapping. */
    static String resolveInheritedMethod(String owner, String name, String descriptor, Set<String> visited) {
        if (!visited.add(owner)) return null;
        for (String parent : parentsOf(owner)) {
            String v = fwdMethod.get(parent + "|" + name + "|" + descriptor);
            if (v != null) return v;
            v = resolveInheritedMethod(parent, name, descriptor, visited);
            if (v != null) return v;
        }
        return null;
    }

    static String resolveInheritedField(String owner, String name, Set<String> visited) {
        if (!visited.add(owner)) return null;
        for (String parent : parentsOf(owner)) {
            String v = fwdField.get(parent + "|" + name);
            if (v != null) return v;
            v = resolveInheritedField(parent, name, visited);
            if (v != null) return v;
        }
        return null;
    }

    private static List<String> parentsOf(String owner) {
        List<String> parents = new ArrayList<>(2);
        String sup = mojSuper.get(owner);
        if (sup != null) parents.add(sup);
        List<String> itfs = mojInterfaces.get(owner);
        if (itfs != null) parents.addAll(itfs);
        return parents;
    }

    /** True when the internal name belongs to Minecraft or a Mojang library, not to our own code. */
    static boolean isGameClass(String owner) {
        return owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/");
    }

    static final Set<String> missedMethod = new TreeSet<>();
    static final Set<String> missedField = new TreeSet<>();
    /** Members that only resolved through the ambiguous by-name table; reported for auditing. */
    static final Set<String> ambiguousMethod = new TreeSet<>();
    static final Set<String> ambiguousField = new TreeSet<>();

    /**
     * Index the Mojang-official class hierarchy from a mojmap jar.
     *
     * <p>Needed because neither the proguard nor the tiny/tsrg files carry supertypes, yet
     * resolving an inherited member requires them. Only net/minecraft and com/mojang classes
     * are indexed; everything else keeps the by-name fallback.
     */
    static void loadHierarchy(Path jar) throws IOException {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class")) continue;
                String internal = name.substring(0, name.length() - 6);
                if (!isGameClass(internal)) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in.readAllBytes());
                    String sup = cr.getSuperName();
                    if (sup != null) mojSuper.put(internal, sup);
                    String[] itfs = cr.getInterfaces();
                    if (itfs != null && itfs.length > 0) mojInterfaces.put(internal, Arrays.asList(itfs));
                } catch (RuntimeException ignored) {
                    // a class we cannot read simply keeps the by-name fallback
                }
            }
        }
        System.out.println("[RemapTool] hierarchy: " + mojSuper.size() + " classes, "
                + mojInterfaces.size() + " with interfaces");
    }

    /* ============================ drivers ============================ */

    static void remapJar(Path in, Path out, Remapper remapper) throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (ZipFile zf = new ZipFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                byte[] data = zf.getInputStream(e).readAllBytes();
                String name = e.getName();
                if (name.endsWith(".class") && !name.equals("module-info.class")) {
                    try {
                        ClassReader cr = new ClassReader(data);
                        ClassWriter cw = new ClassWriter(0);
                        cr.accept(new ClassRemapper(cw, remapper), 0);
                        data = cw.toByteArray();
                        name = remapper.map(name.substring(0, name.length() - 6)) + ".class";
                    } catch (RuntimeException ex) {
                        System.err.println("[RemapTool] skip " + name + ": " + ex);
                        continue;
                    }
                }
                zos.putNextEntry(new ZipEntry(name));
                zos.write(data);
                zos.closeEntry();
            }
        }
    }

    static void remapDir(Path in, Path out, Remapper remapper) throws IOException {
        List<Path> files;
        try (var s = Files.walk(in)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        for (Path p : files) {
            Path rel = in.relativize(p);
            String name = rel.toString().replace('\\', '/');
            byte[] data = Files.readAllBytes(p);
            if (name.endsWith(".class")) {
                ClassReader cr = new ClassReader(data);
                ClassWriter cw = new ClassWriter(0);
                cr.accept(new ClassRemapper(cw, remapper), 0);
                data = cw.toByteArray();
                name = remapper.map(name.substring(0, name.length() - 6)) + ".class";
            }
            Path dst = out.resolve(name);
            Files.createDirectories(dst.getParent());
            Files.write(dst, data);
        }
        if (!missedMethod.isEmpty() || !missedField.isEmpty()) {
            System.err.println("[RemapTool] unmapped MC members: methods="
                    + missedMethod.size() + " fields=" + missedField.size());
            int shown = 0;
            for (String m : missedMethod) { if (shown++ > 20) break; System.err.println("   M " + m); }
            shown = 0;
            for (String f : missedField) { if (shown++ > 20) break; System.err.println("   F " + f); }
        }
        if (!ambiguousMethod.isEmpty() || !ambiguousField.isEmpty()) {
            System.err.println("[RemapTool] by-name fallback used (ambiguous, verify with LinkCheck): methods="
                    + ambiguousMethod.size() + " fields=" + ambiguousField.size());
            for (String m : ambiguousMethod) { System.err.println("   M " + m); }
            for (String f : ambiguousField) { System.err.println("   F " + f); }
        }
    }
}

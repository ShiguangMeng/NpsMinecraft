import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Pairs the mojmap jar (official class names + official member names) with the
 * Forge SRG jar (official class names + SRG member names) to derive an
 * official -> SRG member mapping.
 *
 * Both jars come from the same obfuscated jar processed by renaming tools that
 * rewrite names in place, so member order inside each ClassNode is preserved.
 * This tool verifies that assumption (equal counts + equal descriptors per
 * position) before emitting the mapping.
 */
public final class GenSrgMap {

    public static void main(String[] args) throws Exception {
        File mojJar = new File(args[0]);
        File srgJar = new File(args[1]);
        File outFile = new File(args[2]);

        Map<String, ClassNode> moj = load(mojJar);
        Map<String, ClassNode> srg = load(srgJar);

        int classes = 0, skipped = 0;
        int methodsMatched = 0, methodMismatch = 0, methodCountDiff = 0;
        int fieldsMatched = 0, fieldMismatch = 0, fieldCountDiff = 0;

        StringBuilder sb = new StringBuilder(1 << 22);
        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, ClassNode> e : moj.entrySet()) {
            String name = e.getKey();
            ClassNode a = e.getValue();
            ClassNode b = srg.get(name);
            if (b == null) { skipped++; continue; }
            classes++;

            sb.append(name.replace('/', '.')).append(" -> ")
              .append(name.replace('/', '.')).append(":\n");

            if (a.methods.size() != b.methods.size()) {
                methodCountDiff++;
                if (problems.size() < 20) problems.add("METHOD COUNT " + name + " " + a.methods.size() + " vs " + b.methods.size());
                continue;
            }
            for (int i = 0; i < a.methods.size(); i++) {
                MethodNode ma = a.methods.get(i);
                MethodNode mb = b.methods.get(i);
                if (!ma.desc.equals(mb.desc)) {
                    methodMismatch++;
                    if (problems.size() < 20) problems.add("METHOD DESC " + name + "." + ma.name + ma.desc + " vs " + mb.name + mb.desc);
                    continue;
                }
                methodsMatched++;
                if (!ma.name.equals(mb.name)) {
                    sb.append("    ").append(descToJava(ma.desc)).append(' ').append(ma.name)
                      .append('(').append(argsToJava(ma.desc)).append(") -> ").append(mb.name).append('\n');
                }
            }

            if (a.fields.size() != b.fields.size()) {
                fieldCountDiff++;
                if (problems.size() < 40) problems.add("FIELD COUNT " + name + " " + a.fields.size() + " vs " + b.fields.size());
                continue;
            }
            for (int i = 0; i < a.fields.size(); i++) {
                FieldNode fa = a.fields.get(i);
                FieldNode fb = b.fields.get(i);
                if (!fa.desc.equals(fb.desc)) {
                    fieldMismatch++;
                    if (problems.size() < 40) problems.add("FIELD DESC " + name + "." + fa.name + " " + fa.desc + " vs " + fb.name + " " + fb.desc);
                    continue;
                }
                fieldsMatched++;
                if (!fa.name.equals(fb.name)) {
                    sb.append("    ").append(descToJava(fa.desc)).append(' ').append(fa.name)
                      .append(" -> ").append(fb.name).append('\n');
                }
            }
        }

        try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile), "UTF-8")) {
            w.write(sb.toString());
        }

        System.out.println("classes=" + classes + " skipped(only-in-moj)=" + skipped);
        System.out.println("methods matched=" + methodsMatched + " descMismatch=" + methodMismatch + " countDiff=" + methodCountDiff);
        System.out.println("fields  matched=" + fieldsMatched + " descMismatch=" + fieldMismatch + " countDiff=" + fieldCountDiff);
        System.out.println("out=" + outFile.length() + " bytes");
        for (String p : problems) System.out.println("  ! " + p);
    }

    private static final Map<Integer, String> PRIMS = Map.of(
        Type.BOOLEAN, "boolean", Type.CHAR, "char", Type.BYTE, "byte",
        Type.SHORT, "short", Type.INT, "int", Type.FLOAT, "float",
        Type.LONG, "long", Type.DOUBLE, "double");

    static String descToJava(String desc) {
        Type t = Type.getType(desc);
        return typeName(t);
    }

    static String typeName(Type t) {
        switch (t.getSort()) {
            case Type.VOID: return "void";
            case Type.ARRAY: return typeName(t.getElementType()) + "[]";
            case Type.OBJECT: return t.getClassName();
            default: {
                String p = PRIMS.get(t.getSort());
                return p == null ? "?" : p;
            }
        }
    }

    static String argsToJava(String desc) {
        StringBuilder sb = new StringBuilder();
        for (Type t : Type.getArgumentTypes(desc)) {
            if (sb.length() > 0) sb.append(',');
            sb.append(typeName(t));
        }
        return sb.toString();
    }

    static Map<String, ClassNode> load(File jar) throws IOException {
        Map<String, ClassNode> map = new LinkedHashMap<>(9000);
        try (ZipFile zf = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String n = ze.getName();
                if (!n.endsWith(".class")) continue;
                if (n.startsWith("META-INF/")) continue;
                try (InputStream in = zf.getInputStream(ze)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(in).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    map.put(cn.name, cn);
                }
            }
        }
        return map;
    }
}

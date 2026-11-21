// NPS Minecraft - link-time namespace verifier.
//
// A wrong namespace remap (mojmap left in an intermediary jar, or m_ SRG left in a
// mojmap jar) is invisible at compile time and shows up in game as
// NoSuchMethodError / NoSuchFieldError. This tool catches that class of bug without
// launching Minecraft and without loading a single class:
//
//   * read a flat member index of Minecraft in the *target* namespace
//     (produced by RemapTool `index`, one record per line, tab separated:
//        C <class> <super> <ifaces,comma,separated>
//        M <class> <methodName>
//        F <class> <fieldName>)
//   * walk every class shipped in the mod jar with ASM and collect its references
//   * for each net.minecraft.* / com.mojang.* reference, resolve the owner, then the
//     member, walking the supertype chain exactly the way the JVM would
//
// Avoiding class loading matters: the MC jar references libraries that are frequently
// absent from a minimal classpath, and java.lang.reflect then throws while enumerating
// members, which would mask real problems behind fake ones.

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class LinkCheck {

    private static final Map<String, String> superOf = new HashMap<>(1 << 13);
    private static final Map<String, List<String>> ifacesOf = new HashMap<>(1 << 13);
    private static final Map<String, Set<String>> methodsOf = new HashMap<>(1 << 13);
    private static final Map<String, Set<String>> fieldsOf = new HashMap<>(1 << 13);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: LinkCheck <modJar> <mcIndex.txt>");
            System.exit(2);
        }
        Path modJar = Path.of(args[0]);
        Path index = Path.of(args[1]);

        if (!Files.isRegularFile(index)) {
            System.err.println("[linkcheck] index missing: " + index);
            System.exit(2);
        }

        long t0 = System.nanoTime();
        int indexed = parseIndex(index);
        long indexMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, byte[]> modClasses = readClasses(modJar);
        Set<String> missing = new TreeSet<>();
        Set<String> absent = new TreeSet<>();
        Set<String> owners = new TreeSet<>();
        int refs = 0;
        int memberRefs = 0;

        for (Map.Entry<String, byte[]> entry : modClasses.entrySet()) {
            String self = entry.getKey();
            RefCollector collector = new RefCollector();
            new ClassReader(entry.getValue()).accept(collector, 0);

            for (Ref ref : collector.refs) {
                if (!isMc(ref.owner)) {
                    continue;
                }
                owners.add(ref.owner);
                refs++;
                boolean ownerKnown = superOf.containsKey(ref.owner);
                if (!ownerKnown) {
                    if (ref.kind != Kind.TYPE) {
                        absent.add(self + " -> " + ref.describe());
                    }
                    continue;
                }
                if (ref.kind == Kind.TYPE) {
                    continue;
                }
                memberRefs++;
                boolean found = (ref.kind == Kind.FIELD)
                        ? memberExists(fieldsOf, ref.owner, ref.name, false)
                        : memberExists(methodsOf, ref.owner, ref.name, ref.name.startsWith("<"));
                if (!found) {
                    missing.add(self + " -> " + ref.describe());
                }
            }
        }

        System.out.println("[linkcheck] jar=" + modJar.getFileName() + " index=" + index.getFileName());
        System.out.println("[linkcheck] indexClasses=" + indexed + " in " + indexMs + "ms");
        System.out.println("[linkcheck] modClasses=" + modClasses.size() + " mcRefs=" + refs + " mcMemberRefs=" + memberRefs);
        System.out.println("[linkcheck] mcOwners=" + owners.size());

        if (!absent.isEmpty()) {
            System.out.println("[linkcheck] ABSENT owners (" + absent.size() + "):");
            for (String a : absent) {
                System.out.println("              " + a);
            }
        }

        if (missing.isEmpty() && absent.isEmpty()) {
            System.out.println("[linkcheck] RESULT OK");
            return;
        }
        if (missing.isEmpty()) {
            System.out.println("[linkcheck] RESULT OK (" + absent.size() + " absent owners, no unresolved members)");
            return;
        }
        System.out.println("[linkcheck] RESULT FAIL (" + missing.size() + " unresolved members):");
        for (String m : missing) {
            System.out.println("              " + m);
        }
        System.exit(1);
    }

    private static int parseIndex(Path index) throws IOException {
        int count = 0;
        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            switch (parts[0]) {
                case "C" -> {
                    String cls = parts[1];
                    if (!superOf.containsKey(cls)) {
                        count++;
                    }
                    superOf.put(cls, parts.length > 2 ? parts[2] : "");
                    List<String> itfs = new ArrayList<>();
                    if (parts.length > 3 && !parts[3].isEmpty()) {
                        for (String itf : parts[3].split(",")) {
                            if (!itf.isEmpty()) {
                                itfs.add(itf);
                            }
                        }
                    }
                    ifacesOf.put(cls, itfs);
                }
                case "M" -> methodsOf.computeIfAbsent(parts[1], k -> new HashSet<>()).add(parts[2]);
                case "F" -> fieldsOf.computeIfAbsent(parts[1], k -> new HashSet<>()).add(parts[2]);
                default -> {
                    // unknown record type; ignore
                }
            }
        }
        return count;
    }

    /**
     * Resolve a member the way the JVM does: check the declaring class, then its superclass
     * chain, then interfaces. Constructors are only ever looked up on the owner itself.
     */
    private static boolean memberExists(Map<String, Set<String>> table, String owner, String name, boolean ownerOnly) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            if (ownerOnly && !current.equals(owner)) {
                continue;
            }
            Set<String> members = table.get(current);
            if (members != null && members.contains(name)) {
                return true;
            }
            if (ownerOnly) {
                continue;
            }
            String sup = superOf.get(current);
            if (sup != null && !sup.isEmpty()) {
                queue.add(sup);
            }
            List<String> itfs = ifacesOf.get(current);
            if (itfs != null) {
                queue.addAll(itfs);
            }
        }
        return false;
    }

    private enum Kind { TYPE, FIELD, METHOD }

    private static final class Ref {
        final Kind kind;
        final String owner;
        final String name;
        final String desc;

        Ref(Kind kind, String owner, String name, String desc) {
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        String describe() {
            String simple = owner.substring(owner.lastIndexOf('/') + 1);
            if (kind == Kind.TYPE) {
                return "TYPE " + owner;
            }
            return kind + " " + simple + "." + name + (desc == null ? "" : desc);
        }
    }

    private static final class RefCollector extends ClassVisitor {
        final List<Ref> refs = new ArrayList<>();

        RefCollector() {
            super(Opcodes.ASM9);
        }

        private void typeRef(String internalName) {
            if (internalName == null || internalName.startsWith("[")) {
                return;
            }
            refs.add(new Ref(Kind.TYPE, internalName, null, null));
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            typeRef(superName);
            if (interfaces != null) {
                for (String itf : interfaces) {
                    typeRef(itf);
                }
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            for (String t : typesIn(descriptor)) {
                typeRef(t);
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            for (String t : typesIn(descriptor)) {
                typeRef(t);
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    refs.add(new Ref(Kind.FIELD, owner, name, descriptor));
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    refs.add(new Ref(Kind.METHOD, owner, name, descriptor));
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    typeRef(type);
                }
            };
        }
    }

    private static List<String> typesIn(String descriptor) {
        List<String> out = new ArrayList<>();
        if (descriptor == null) {
            return out;
        }
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) {
                    break;
                }
                out.add(descriptor.substring(i + 1, end));
                i = end + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    private static boolean isMc(String internalName) {
        return internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/");
    }

    private static Map<String, byte[]> readClasses(Path jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                String binary = e.getName().substring(0, e.getName().length() - 6).replace('/', '.');
                try (InputStream in = zip.getInputStream(e)) {
                    out.put(binary, in.readAllBytes());
                }
            }
        }
        return out;
    }
}

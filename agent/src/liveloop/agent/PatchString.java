package liveloop.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Patches string constants in game classes: replaces an LDC string value in the bytecode
 * and (live mode) redefines the class in the running JVM. This bypasses source compilation
 * entirely, so yGuard keyword-shadow classes (which cannot be recompiled from CFR output)
 * are fully supported.
 *
 * Live:   POST /api/patch-string {"class":"com.fs.starfarer.title.ObfC0871","old":"战役模式","new":"任务"}
 *         (in-memory redefine; reverts on game restart)
 * Persist (run while the game is CLOSED):
 *         java -cp hotswap-agent.jar liveloop.agent.PatchString <jar-path> <class-internal> <old> <new>
 */
public final class PatchString {
    private PatchString() {}

    /** Transform class bytes: replace LDC strings AND ConstantValue field constants equal to oldVal. */
    public static byte[] transform(byte[] bytes, String oldVal, String newVal, AtomicInteger replaced) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if (value instanceof String && value.equals(oldVal)) {
                    replaced.incrementAndGet();
                    return super.visitField(access, name, desc, sig, newVal);
                }
                return super.visitField(access, name, desc, sig, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitLdcInsn(Object cst) {
                        if (cst instanceof String && cst.equals(oldVal)) {
                            replaced.incrementAndGet();
                            super.visitLdcInsn(newVal);
                        } else {
                            super.visitLdcInsn(cst);
                        }
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    /** Locate <internalName>.class in one of the classpath jars and return its bytes. */
    static byte[] readOriginalClass(String internalName) throws Exception {
        String cp = System.getProperty("java.class.path", "");
        String entryName = internalName.replace('.', '/') + ".class";
        for (String p : cp.split(java.io.File.pathSeparator)) {
            if (!p.toLowerCase().endsWith(".jar")) continue;
            File f = new File(p);
            if (!f.isFile()) continue;
            try (JarFile jf = new JarFile(f)) {
                JarEntry je = jf.getJarEntry(entryName);
                if (je != null) {
                    try (java.io.InputStream in = jf.getInputStream(je)) {
                        return in.readAllBytes();
                    }
                }
            } catch (Exception ignore) {}
        }
        throw new IllegalStateException("class entry not found in classpath jars: " + entryName);
    }

    /** Live patch: transform + redefine in the running JVM. Returns {ok, class, replaced}. */
    public static Map<String, Object> patchLive(String className, String oldVal, String newVal) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String internal = className.replace('.', '/');
            byte[] original = readOriginalClass(internal);
            AtomicInteger n = new AtomicInteger();
            byte[] patched = transform(original, oldVal, newVal, n);
            if (n.get() == 0) {
                out.put("ok", false);
                out.put("error", "constant not found in " + className);
                return out;
            }
            Map<String, byte[]> one = new LinkedHashMap<>();
            one.put(internal, patched);
            Map<String, Object> res = Redefiner.redefine(one, null);
            String status = String.valueOf(res.get(internal));
            out.put("ok", "redefined".equals(status));
            out.put("class", className);
            out.put("replaced", n.get());
            if (!"redefined".equals(status)) out.put("error", status);
            Log.log("patch-string " + className + ": '" + oldVal + "'->'" + newVal + "' replaced=" + n.get() + " status=" + status);
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", String.valueOf(t));
            Log.log("patch-string failed: " + t);
        }
        return out;
    }

    /** Offline persistent mode: rewrite the entry inside a jar copy (game must be closed). */
    public static void patchJarFile(String jarPath, String internalName, String oldVal, String newVal) throws Exception {
        File src = new File(jarPath);
        File bak = new File(jarPath + ".bak_patchstring");
        byte[] original = null;
        try (JarFile jf = new JarFile(src)) {
            JarEntry je = jf.getJarEntry(internalName + ".class");
            if (je == null) throw new IllegalStateException("entry not found: " + internalName);
            try (java.io.InputStream in = jf.getInputStream(je)) { original = in.readAllBytes(); }
        }
        AtomicInteger n = new AtomicInteger();
        byte[] patched = transform(original, oldVal, newVal, n);
        if (n.get() == 0) throw new IllegalStateException("constant not found: " + oldVal);

        try (FileInputStream fin = new FileInputStream(src);
             JarFile jf = new JarFile(src);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(bak))) {
            Enumeration<JarEntry> entries = jf.entries();
            byte[] buf = new byte[65536];
            while (entries.hasMoreElements()) {
                JarEntry je = entries.nextElement();
                if (je.isDirectory()) continue;
                if (je.getName().equals(internalName + ".class")) {
                    jos.putNextEntry(new ZipEntry(je.getName()));
                    jos.write(patched);
                } else {
                    jos.putNextEntry(new ZipEntry(je.getName()));
                    try (java.io.InputStream in = jf.getInputStream(je)) {
                        int r;
                        while ((r = in.read(buf)) > 0) jos.write(buf, 0, r);
                    }
                }
                jos.closeEntry();
            }
        }
        java.nio.file.Files.copy(bak.toPath(), src.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("PATCH-OK replaced=" + n.get() + " (backup: " + bak.getName() + ")");
    }

    /** Offline CLI: java -cp hotswap-agent.jar liveloop.agent.PatchString <jar> <internalName> <old> <new> */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.out.println("usage: PatchString <jar> <class-internal-name> <old-string> <new-string>");
            System.exit(2);
        }
        patchJarFile(args[0], args[1], args[2], args[3]);
    }
}

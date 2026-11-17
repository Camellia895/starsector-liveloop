package liveloop.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Injects {@code GameThreadPump.pump()} at the entry of every BaseGameState.processInput
 * method. processInput runs per-frame on the game main thread, at the same stage as the
 * game's own UI button handlers — i.e. outside engine plugin traversal, which makes it a
 * safe point for battle rebuilds.
 */
public final class InjectTransformer implements ClassFileTransformer {
    /** Concrete per-frame processInput implementations (BaseGameState.processInput is ABSTRACT). */
    static final java.util.Set<String> TARGET_CLASSES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com/fs/starfarer/title/TitleScreenState",
            "com/fs/starfarer/campaign/CampaignState",
            "com/fs/starfarer/combat/CombatState"));
    static final String PUMP_OWNER = "liveloop/agent/GameThreadPump";
    private static final String MARKER = "GameThreadPump";

    public InjectTransformer() {
        HotSwapAgent.logStage("inject transformer registered");
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] bytes) {
        if (bytes == null || !TARGET_CLASSES.contains(className)) return null;
        // idempotence: skip if the marker string is already in the constant pool
        if (indexOf(bytes, MARKER) >= 0) return null;
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(cr, 0); // frames copied; entry-frame unaffected by our call
            cr.accept(new CV(cw), 0);
            byte[] out = cw.toByteArray();
            HotSwapAgent.logStage("pump injected into " + className);
            return out;
        } catch (Throwable t) {
            HotSwapAgent.logStage("injection failed: " + t);
            return null;
        }
    }

    private static final class CV extends ClassVisitor {
        CV(ClassWriter cw) { super(Opcodes.ASM9, cw); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            // every concrete processInput override; abstract methods never reach visitCode
            if (name.equals("processInput") && (access & Opcodes.ACC_ABSTRACT) == 0) {
                return new MV(mv);
            }
            return mv;
        }
    }

    private static final class MV extends MethodVisitor {
        MV(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitMethodInsn(Opcodes.INVOKESTATIC, PUMP_OWNER, "pump", "()V", false);
        }
    }

    private static int indexOf(byte[] b, String s) {
        byte[] pat = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= b.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) if (b[i + j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }
}

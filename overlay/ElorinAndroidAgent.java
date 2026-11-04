import java.awt.Canvas;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import javax.swing.SwingUtilities;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Compatibility changes for the pinned Elorin client, applied without altering its JAR. */
public final class ElorinAndroidAgent {
    private ElorinAndroidAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        System.err.println("Elorin Android: compatibility agent active");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String className,
                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) {
                return transformClass(className, classfileBuffer);
            }
        });
    }

    static byte[] transformClass(String className, byte[] original) {
        if ("hF".equals(className)) return transformGameEngine(original);
        if ("Nj".equals(className)) return transformClientUi(original);
        return null;
    }

    private static byte[] transformGameEngine(byte[] original) {
        try {
            ClassReader reader = new ClassReader(original);
            ClassWriter writer = new ClassWriter(reader, 0);
            int[] replacements = {0};
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM8, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!"r".equals(name) || !"()V".equals(descriptor)) {
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }

                    // hF.r() is called synchronously by hm.N() while Nj builds the
                    // window on the EDT. Delay only the game-thread start, leaving
                    // all other initialization (including hm.N()) synchronous.
                    MethodVisitor replacement = super.visitMethod(
                            access, name, descriptor, signature, exceptions);
                    replacement.visitCode();
                    replacement.visitVarInsn(Opcodes.ALOAD, 0);
                    replacement.visitInsn(Opcodes.ICONST_1);
                    replacement.visitMethodInsn(Opcodes.INVOKESTATIC, "ElorinAndroidAgent",
                            "startLater", "(Ljava/lang/Runnable;I)V", false);
                    replacement.visitInsn(Opcodes.RETURN);
                    replacement.visitMaxs(2, 1);
                    replacement.visitEnd();
                    replacements[0]++;
                    return null;
                }
            };
            reader.accept(visitor, 0);
            if (replacements[0] != 1) {
                System.err.println("Elorin Android: unexpected hF startup structure ("
                        + replacements[0] + " matches); client compatibility needs review");
                return null;
            }
            System.err.println("Elorin Android: game-thread startup transformation installed");
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("Elorin Android: could not install the game-thread startup fix");
            failure.printStackTrace();
            return null;
        }
    }

    private static byte[] transformClientUi(byte[] original) {
        try {
            ClassReader reader = new ClassReader(original);
            ClassWriter writer = new ClassWriter(reader, 0);
            int[] guards = {0};
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM8, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    MethodVisitor method = super.visitMethod(
                            access, name, descriptor, signature, exceptions);
                    if (!"H".equals(name) || !"(Z)V".equals(descriptor)) return method;

                    // This Elorin ClientUI version directly sizes g.Q, unlike current
                    // RuneLite, which sizes the client container. During Android startup
                    // the Canvas does not exist yet. Let frame construction continue;
                    // hF.w() creates the Canvas and hF.B() applies the active game size.
                    return new MethodVisitor(Opcodes.ASM8, method) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, "Nj", "g", "Lhm;");
                            super.visitFieldInsn(Opcodes.GETFIELD, "hm", "Q", "Ljava/awt/Canvas;");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "ElorinAndroidAgent",
                                    "canvasMissing", "(Ljava/awt/Canvas;)Z", false);
                            Label canvasReady = new Label();
                            super.visitJumpInsn(Opcodes.IFEQ, canvasReady);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(canvasReady);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            guards[0]++;
                        }
                    };
                }
            };
            reader.accept(visitor, 0);
            if (guards[0] != 1) {
                System.err.println("Elorin Android: unexpected Nj sizing structure ("
                        + guards[0] + " matches); client compatibility needs review");
                return null;
            }
            System.err.println("Elorin Android: Canvas startup guard installed");
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("Elorin Android: could not install the Canvas startup guard");
            failure.printStackTrace();
            return null;
        }
    }

    /** Called only by the transformed hF.r() method. */
    public static void startLater(Runnable game, int priority) {
        SwingUtilities.invokeLater(() -> {
            System.err.println("Elorin Android: starting deferred game thread");
            // Preserve hF.m(Runnable, int): construct, start, then set priority.
            Thread thread = new Thread(game);
            thread.start();
            thread.setPriority(priority);
        });
    }

    /** Called only by the transformed Nj.H(boolean) method. */
    public static boolean canvasMissing(Canvas canvas) {
        if (canvas != null) return false;
        System.err.println("Elorin Android: Canvas not created; using game-engine sizing");
        return true;
    }

    /** Structural test hooks; not used by the running agent. */
    public static byte[] transformForTest(byte[] hFClass) {
        return transformClass("hF", hFClass);
    }

    public static byte[] transformUiForTest(byte[] njClass) {
        return transformClass("Nj", njClass);
    }
}

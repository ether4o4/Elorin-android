import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.security.ProtectionDomain;

import javax.swing.SwingUtilities;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Compatibility changes for the pinned Elorin client, applied without altering its JAR. */
public final class ElorinAndroidAgent {
    private ElorinAndroidAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
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
        if (!"QP".equals(className)) return null;
        try {
            ClassReader reader = new ClassReader(original);
            ClassWriter writer = new ClassWriter(reader, 0);
            int[] replacements = {0};
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM8, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM8, delegate) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                String descriptor, boolean isInterface) {
                            if (opcode == Opcodes.INVOKEVIRTUAL && "hm".equals(owner)
                                    && "N".equals(name) && "()V".equals(descriptor)) {
                                // QP is constructed on the EDT. Queueing this call lets
                                // Nj finish pack()/setVisible(true) before the game thread
                                // creates its Canvas and requests a Graphics peer.
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "ElorinAndroidAgent",
                                        "startLater", "(Ljava/lang/Object;)V", false);
                                replacements[0]++;
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    };
                }
            };
            reader.accept(visitor, 0);
            if (replacements[0] != 1) {
                System.err.println("Elorin Android: unexpected QP startup structure ("
                        + replacements[0] + " matches); client compatibility needs review");
                return null;
            }
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("Elorin Android: could not install the canvas startup fix");
            failure.printStackTrace();
            return null;
        }
    }

    /** Called only by the transformed QP constructor. */
    public static void startLater(Object game) {
        SwingUtilities.invokeLater(() -> {
            try {
                game.getClass().getMethod("N").invoke(game);
            } catch (InvocationTargetException failure) {
                failure.getCause().printStackTrace();
            } catch (ReflectiveOperationException failure) {
                failure.printStackTrace();
            }
        });
    }

    /** Structural test hook; not used by the running agent. */
    public static byte[] transformForTest(byte[] qpClass) {
        return transformClass("QP", qpClass);
    }
}

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

public final class ElorinAndroidAgentTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected game client JAR path");
        byte[] original;
        try (ZipFile jar = new ZipFile(Path.of(args[0]).toFile())) {
            ZipEntry entry = jar.getEntry("QP.class");
            if (entry == null) throw new AssertionError("Pinned client has no QP.class");
            original = jar.getInputStream(entry).readAllBytes();
        }
        byte[] transformed = ElorinAndroidAgent.transformForTest(original);
        if (transformed == null) throw new AssertionError("Agent did not recognize pinned QP.class");

        int[] deferredCalls = {0};
        int[] originalCalls = {0};
        ClassReader reader = new ClassReader(transformed);
        reader.accept(new ClassVisitor(Opcodes.ASM8) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM8) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && "ElorinAndroidAgent".equals(owner)
                                && "startLater".equals(name) && "(Ljava/lang/Object;)V".equals(descriptor)) {
                            deferredCalls[0]++;
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL && "hm".equals(owner)
                                && "N".equals(name) && "()V".equals(descriptor)) {
                            originalCalls[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        if (deferredCalls[0] != 1 || originalCalls[0] != 0) {
            throw new AssertionError("Unexpected transformed startup calls");
        }
        System.out.println("Elorin canvas startup deferral verified in pinned QP.class");
    }
}

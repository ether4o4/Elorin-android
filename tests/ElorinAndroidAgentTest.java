import java.net.URL;
import java.net.URLClassLoader;
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
        byte[] originalHf;
        byte[] originalQp;
        byte[] originalNj;
        try (ZipFile jar = new ZipFile(Path.of(args[0]).toFile())) {
            ZipEntry hFEntry = jar.getEntry("hF.class");
            ZipEntry qpEntry = jar.getEntry("QP.class");
            ZipEntry njEntry = jar.getEntry("Nj.class");
            if (hFEntry == null || qpEntry == null || njEntry == null) {
                throw new AssertionError("Pinned client has no hF.class, QP.class, or Nj.class");
            }
            originalHf = jar.getInputStream(hFEntry).readAllBytes();
            originalQp = jar.getInputStream(qpEntry).readAllBytes();
            originalNj = jar.getInputStream(njEntry).readAllBytes();
        }

        byte[] transformed = ElorinAndroidAgent.transformForTest(originalHf);
        if (transformed == null) throw new AssertionError("Agent did not recognize pinned hF.class");
        byte[] transformedNj = ElorinAndroidAgent.transformUiForTest(originalNj);
        if (transformedNj == null) throw new AssertionError("Agent did not recognize pinned Nj.class");
        if (ElorinAndroidAgent.transformClass("QP", originalQp) != null) {
            throw new AssertionError("QP must remain untransformed so hm.N() stays synchronous");
        }

        int[] deferredCalls = {0};
        int[] originalThreadStarts = {0};
        ClassReader reader = new ClassReader(transformed);
        reader.accept(new ClassVisitor(Opcodes.ASM8) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                boolean startupMethod = "r".equals(name) && "()V".equals(descriptor);
                return new MethodVisitor(Opcodes.ASM8) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        if (startupMethod && opcode == Opcodes.INVOKESTATIC
                                && "ElorinAndroidAgent".equals(owner) && "startLater".equals(name)
                                && "(Ljava/lang/Runnable;I)V".equals(descriptor)) {
                            deferredCalls[0]++;
                        }
                        if (startupMethod && opcode == Opcodes.INVOKEVIRTUAL && "hF".equals(owner)
                                && "m".equals(name) && "(Ljava/lang/Runnable;I)Ljava/lang/Thread;".equals(descriptor)) {
                            originalThreadStarts[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        if (deferredCalls[0] != 1 || originalThreadStarts[0] != 0) {
            throw new AssertionError("Unexpected transformed hF.r() game-thread start");
        }

        int[] synchronousGameInitializers = {0};
        new ClassReader(originalQp).accept(new ClassVisitor(Opcodes.ASM8) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM8) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && "hm".equals(owner)
                                && "N".equals(name) && "()V".equals(descriptor)) {
                            synchronousGameInitializers[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        if (synchronousGameInitializers[0] != 1) {
            throw new AssertionError("Pinned QP no longer has exactly one synchronous hm.N() call");
        }

        int[] canvasGuards = {0};
        int[] originalCanvasSizes = {0};
        new ClassReader(transformedNj).accept(new ClassVisitor(Opcodes.ASM8) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                boolean sizingMethod = "H".equals(name) && "(Z)V".equals(descriptor);
                return new MethodVisitor(Opcodes.ASM8) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        if (sizingMethod && opcode == Opcodes.INVOKESTATIC
                                && "ElorinAndroidAgent".equals(owner) && "canvasMissing".equals(name)
                                && "(Ljava/awt/Canvas;)Z".equals(descriptor)) {
                            canvasGuards[0]++;
                        }
                        if (sizingMethod && opcode == Opcodes.INVOKEVIRTUAL
                                && "java/awt/Canvas".equals(owner) && "setSize".equals(name)) {
                            originalCanvasSizes[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        if (canvasGuards[0] != 1 || originalCanvasSizes[0] != 1) {
            throw new AssertionError("Nj.H() must guard startup without removing existing Canvas sizing (guards="
                    + canvasGuards[0] + ", setSize calls=" + originalCanvasSizes[0] + ")");
        }
        if (!ElorinAndroidAgent.canvasMissing(null) || ElorinAndroidAgent.canvasMissing(new java.awt.Canvas())) {
            throw new AssertionError("Canvas guard must trigger only while the Canvas is absent");
        }

        // Link the actual transformed class with JVM verification enabled. This
        // catches invalid stack-map frames even though startup needs a phone/AWT test.
        URL clientUrl = Path.of(args[0]).toUri().toURL();
        class TransformedLoader extends ClassLoader {
            TransformedLoader(ClassLoader parent) { super(parent); }
            Class<?> linkTransformedUi() {
                Class<?> type = defineClass("Nj", transformedNj, 0, transformedNj.length);
                resolveClass(type);
                return type;
            }
        }
        try (URLClassLoader gameClasses = new URLClassLoader(new URL[]{clientUrl},
                ElorinAndroidAgentTest.class.getClassLoader())) {
            Class<?> linked = new TransformedLoader(gameClasses).linkTransformedUi();
            if (!"Nj".equals(linked.getName())) {
                throw new AssertionError("Transformed Nj class did not link");
            }
        }

        System.out.println("Elorin startup fixes verified; synchronous initialization and Canvas sizing preserved");
    }
}

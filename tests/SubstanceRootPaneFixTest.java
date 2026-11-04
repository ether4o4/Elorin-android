import java.awt.Color;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import javax.swing.JRootPane;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/**
 * Verifies the Substance title-pane fix against the pinned radiance-substance JAR:
 * the original propertyChange("background") throws the exact NullPointerException
 * seen on the phone when the window has no title pane, and the transformed class
 * links under full verification and no longer throws.
 */
public final class SubstanceRootPaneFixTest {
    private static final String CLASS_NAME = ElorinAndroidAgent.SUBSTANCE_ROOT_PANE_UI.replace('/', '.');

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Expected Radiance JAR paths (substance first)");
        byte[] original;
        try (ZipFile jar = new ZipFile(Path.of(args[0]).toFile())) {
            var entry = jar.getEntry(ElorinAndroidAgent.SUBSTANCE_ROOT_PANE_UI + ".class");
            if (entry == null) throw new AssertionError("Pinned Substance JAR has no SubstanceRootPaneUI");
            original = jar.getInputStream(entry).readAllBytes();
        }
        byte[] transformed = ElorinAndroidAgent.transformClass(ElorinAndroidAgent.SUBSTANCE_ROOT_PANE_UI, original);
        if (transformed == null) throw new AssertionError("Agent did not recognize pinned SubstanceRootPaneUI");

        int[] helperCalls = {0};
        int[] directSetBackground = {0};
        int[] titlePaneLookups = {0};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM8) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                boolean target = "propertyChange".equals(name)
                        && "(Ljava/beans/PropertyChangeEvent;)V".equals(descriptor);
                return new MethodVisitor(Opcodes.ASM8) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        if (!target) return;
                        if ("ElorinAndroidAgent".equals(owner) && "setTitlePaneBackground".equals(name)) helperCalls[0]++;
                        if ("javax/swing/JComponent".equals(owner) && "setBackground".equals(name)) directSetBackground[0]++;
                        if ("getTitlePaneComponent".equals(name)) titlePaneLookups[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        if (helperCalls[0] != 1 || directSetBackground[0] != 0 || titlePaneLookups[0] != 1) {
            throw new AssertionError("Unexpected transformed propertyChange (helper=" + helperCalls[0]
                    + ", direct=" + directSetBackground[0] + ", lookups=" + titlePaneLookups[0] + ")");
        }

        URL[] urls = new URL[args.length];
        for (int i = 0; i < args.length; i++) urls[i] = Path.of(args[i]).toUri().toURL();
        try (URLClassLoader radiance = new URLClassLoader(urls, SubstanceRootPaneFixTest.class.getClassLoader())) {
            // Original: reproduce the phone failure.
            Object originalUi = radiance.loadClass(CLASS_NAME).getConstructor().newInstance();
            try {
                fireBackground(originalUi);
                throw new AssertionError("Original Substance class unexpectedly accepted a missing title pane");
            } catch (NullPointerException expected) {
                if (!String.valueOf(expected.getMessage()).contains("getTitlePaneComponent")) {
                    throw new AssertionError("Original failure differs from the device log", expected);
                }
            }

            // Transformed: link with full verification and run the same event.
            class TransformedLoader extends ClassLoader {
                TransformedLoader(ClassLoader parent) { super(parent); }
                Class<?> define() {
                    Class<?> type = defineClass(CLASS_NAME, transformed, 0, transformed.length);
                    resolveClass(type);
                    return type;
                }
            }
            Object fixedUi = new TransformedLoader(radiance).define().getConstructor().newInstance();
            fireBackground(fixedUi);
        }
        System.out.println("Substance title-pane fix verified: original NPE reproduced, transformed class links and completes");
    }

    private static void fireBackground(Object ui) {
        JRootPane root = new JRootPane();
        ((PropertyChangeListener) ui).propertyChange(
                new PropertyChangeEvent(root, "background", null, Color.BLACK));
    }
}

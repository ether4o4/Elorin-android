package net.kdt.pojavlaunch;

import android.content.Context;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Prepares the pinned native system-ID library without changing its algorithm. */
final class ElorinNativeCompat {
    private static final String NATIVE_ENTRY = "linux-aarch64/librsps.so";
    private static final int MAX_NATIVE_SIZE = 100_000;

    private ElorinNativeCompat() {}

    static void prepare(Context context, File gameClient, File workingDir, File nativeDir)
            throws IOException {
        if (!workingDir.isDirectory() && !workingDir.mkdirs()) {
            throw new IOException("Cannot create Elorin working directory");
        }
        if (!nativeDir.isDirectory() && !nativeDir.mkdirs()) {
            throw new IOException("Cannot create Android native directory");
        }

        byte[] original;
        try (ZipFile zip = new ZipFile(gameClient)) {
            ZipEntry entry = zip.getEntry(NATIVE_ENTRY);
            if (entry == null) throw new IOException("Game client has no " + NATIVE_ENTRY);
            try (InputStream in = zip.getInputStream(entry)) {
                original = readBounded(in, MAX_NATIVE_SIZE);
            }
        }

        byte[] patched = RspsElfPatcher.patch(original);
        File target = new File(nativeDir, "librsps.so");
        if (!target.isFile() || !RspsElfPatcher.PATCHED_SHA256.equals(hash(target))) {
            writeAtomically(target, patched);
        }

        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) {
            throw new IOException("Android did not provide a stable device ID");
        }

        // Android IDs are normally 64-bit hex values. Hashing with an app-specific
        // domain produces the 32-hex-character machine-id format expected by the
        // original native library without storing the raw Android ID in the game
        // working directory or inventing a random system ID.
        String machineId = RspsElfPatcher.sha256(("Elorin Android device ID v1\0"
                + androidId.trim()).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
        File machineIdFile = new File(workingDir, "machine-id");
        byte[] contents = (machineId + "\n").getBytes(StandardCharsets.US_ASCII);
        if (!machineIdFile.isFile() || !Arrays.equals(contents, readExisting(machineIdFile, 64))) {
            writeAtomically(machineIdFile, contents);
        }
    }

    private static byte[] readBounded(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > maximum) throw new IOException("Native library is too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static byte[] readExisting(File file, int maximum) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readBounded(in, maximum + 1);
        }
    }

    private static String hash(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return RspsElfPatcher.sha256(readBounded(in, MAX_NATIVE_SIZE + 1));
        }
    }

    private static void writeAtomically(File target, byte[] contents) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(contents);
                out.getFD().sync();
            }
            if (target.isFile() && !target.delete()) {
                throw new IOException("Cannot replace " + target.getName());
            }
            if (!temporary.renameTo(target)) {
                throw new IOException("Cannot install " + target.getName());
            }
        } finally {
            temporary.delete();
        }
    }
}

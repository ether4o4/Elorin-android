package net.kdt.pojavlaunch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Narrow binary compatibility patch for the pinned Linux/AArch64 librsps.
 *
 * The system-ID algorithm and its embedded constants are left untouched. The
 * patch only changes libc linkage, removes GNU symbol-version metadata that
 * Android's Bionic linker cannot resolve, and makes the two machine-id paths
 * relative to the JVM working directory.
 */
public final class RspsElfPatcher {
    public static final String ORIGINAL_SHA256 =
            "d801cdf1888479b293a841433d791d24d8c7b9f61768b956275df84ccf1bcf7e";
    public static final String PATCHED_SHA256 =
            "f79f472059a54f71db361dda4566c810b1d24c403caafe66b6cf388548e28438";

    private static final int EXPECTED_SIZE = 67688;
    private static final int GNU_VERSION_OFFSET = 0x588;
    private static final int GNU_VERSION_SIZE = 0x2e;
    private static final int DYNAMIC_OFFSET = 0xfdf0;
    private static final int DYNAMIC_SIZE = 0x1d0;
    private static final long DT_VERSYM = 0x6ffffff0L;
    private static final long DT_VERNEED = 0x6ffffffeL;
    private static final long DT_VERNEEDNUM = 0x6fffffffL;
    private static final long DT_ELORIN_IGNORED = 0x6fffd00L;

    private RspsElfPatcher() {}

    public static byte[] patch(byte[] original) throws IOException {
        if (original.length != EXPECTED_SIZE || !ORIGINAL_SHA256.equals(sha256(original))) {
            throw new IOException("Unexpected linux-aarch64/librsps.so; refresh the Android compatibility patch");
        }

        byte[] patched = original.clone();
        replaceOnce(patched, "libc.so.6", "libc.so");
        replaceOnce(patched, "/etc/machine-id", "machine-id");
        replaceOnce(patched, "/var/lib/dbus/machine-id", "machine-id");

        // The pinned ELF has 23 half-word .gnu.version entries. Index zero is
        // local; all other symbols become unversioned/global for Bionic.
        for (int index = 0; index < GNU_VERSION_SIZE / 2; index++) {
            putU16(patched, GNU_VERSION_OFFSET + index * 2, index == 0 ? 0 : 1);
        }

        // Keep the dynamic array's shape but neutralize its three GNU version
        // lookups. DT_ELORIN_IGNORED is in the OS-specific, ignored tag range.
        int versionTags = 0;
        for (int offset = DYNAMIC_OFFSET; offset < DYNAMIC_OFFSET + DYNAMIC_SIZE; offset += 16) {
            long tag = getI64(patched, offset);
            if (tag == DT_VERSYM || tag == DT_VERNEED || tag == DT_VERNEEDNUM) {
                putI64(patched, offset, DT_ELORIN_IGNORED);
                versionTags++;
            }
        }
        if (versionTags != 3 || !PATCHED_SHA256.equals(sha256(patched))) {
            throw new IOException("librsps Android patch did not produce the expected binary");
        }
        return patched;
    }

    private static void replaceOnce(byte[] data, String original, String replacement) throws IOException {
        byte[] needle = (original + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] replacementBytes = replacement.getBytes(StandardCharsets.UTF_8);
        if (replacementBytes.length >= needle.length) {
            throw new IOException("Invalid ELF string replacement");
        }
        int match = -1;
        for (int i = 0; i <= data.length - needle.length; i++) {
            boolean matches = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                if (match >= 0) throw new IOException("Duplicate ELF string: " + original);
                match = i;
            }
        }
        if (match < 0) throw new IOException("Missing ELF string: " + original);
        System.arraycopy(replacementBytes, 0, data, match, replacementBytes.length);
        for (int i = match + replacementBytes.length; i < match + needle.length; i++) data[i] = 0;
    }

    private static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private static long getI64(byte[] data, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) value = (value << 8) | (data[offset + i] & 0xffL);
        return value;
    }

    private static void putI64(byte[] data, int offset, long value) {
        for (int i = 0; i < 8; i++) data[offset + i] = (byte) (value >>> (i * 8));
    }

    public static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            char[] hex = new char[digest.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}

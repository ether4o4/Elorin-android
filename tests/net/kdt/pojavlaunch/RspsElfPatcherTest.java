package net.kdt.pojavlaunch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class RspsElfPatcherTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected original librsps.so path");
        byte[] original = Files.readAllBytes(Path.of(args[0]));
        byte[] patched = RspsElfPatcher.patch(original);
        if (!Arrays.equals(patched, RspsElfPatcher.patch(original))) {
            throw new AssertionError("ELF patch is not deterministic");
        }
        if (!RspsElfPatcher.PATCHED_SHA256.equals(RspsElfPatcher.sha256(patched))) {
            throw new AssertionError("Unexpected patched ELF hash");
        }
        System.out.println("librsps Android ELF patch verified: " + RspsElfPatcher.PATCHED_SHA256);
    }
}

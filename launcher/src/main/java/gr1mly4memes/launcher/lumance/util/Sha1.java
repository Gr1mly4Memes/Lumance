/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.launcher.lumance.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/** SHA-1 hashing, matching the checksums Mojang publishes. */
public final class Sha1 {
    private Sha1() {
    }

    /**
     * Where remembered hashes live. Left null unless {@link #useCache} is called, in which case
     * every call hashes in full - correct, and what the launcher did before the cache existed.
     */
    private static HashCache cache;

    /** Points the hash helpers at a cache directory, so unchanged files are not re-read. */
    public static void useCache(File directory) {
        cache = new HashCache(directory.toPath());
    }

    /** Writes the remembered hashes out, once the launch is done with them. */
    public static void flushCache() {
        if (cache != null) {
            cache.flush();
        }
    }

    /**
     * Hashes a file, reusing the remembered value when its size and timestamp are unchanged.
     *
     * <p>This sits on the launcher's hot path: the bundle, every extracted library and every mod jar
     * is verified on each boot, and re-reading those bytes was the largest single cost in starting.
     */
    private static String cached(File file, String algorithm) {
        if (cache != null) {
            String known = cache.lookup(file, algorithm);
            if (known != null) {
                return known;
            }
        }
        // Straight to the uncached path: going via hash() would come back here and recurse.
        String computed = hashUncached(file, algorithm);
        if (cache != null) {
            cache.remember(file, algorithm, computed);
        }
        return computed;
    }

    public static String of(File file) {
        return cached(file, "SHA-1");
    }

    public static String of(InputStream in) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (DigestInputStream din = new DigestInputStream(in, digest)) {
                din.readAllBytes();
            }
            return hex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    public static String sha256(File file) {
        return cached(file, "SHA-256");
    }

    /**
     * Hashes a file with the named algorithm, reusing the remembered value when unchanged.
     *
     * <p>This is the entry point the download and extraction steps use, and it was the single
     * biggest cost in starting the launcher: the Mojang bundle and every extracted library are
     * verified through here on every boot.
     */
    public static String hash(File file, String algorithm) {
        return cached(file, algorithm);
    }

    /** Always reads the file, bypassing the cache. For bytes known to be new. */
    public static String hashUncached(File file, String algorithm) {
        try (InputStream in = new FileInputStream(file)) {
            return hash(in, algorithm);
        } catch (Exception e) {
            return null;
        }
    }

    /** SHA-256 of an in-memory string, for building cache fingerprints. */
    public static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return null;
        }
    }

    private static String hash(InputStream in, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (DigestInputStream din = new DigestInputStream(in, digest)) {
                din.readAllBytes();
            }
            return hex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Short, filename-safe fingerprint used for cache file names. */
    public static String shortId(String sha1) {
        return sha1 == null ? "unknown" : sha1.substring(0, Math.min(12, sha1.length()));
    }
}

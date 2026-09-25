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

    public static String of(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return of(in);
        } catch (Exception e) {
            return null;
        }
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
        try (InputStream in = new FileInputStream(file)) {
            return hash(in, "SHA-256");
        } catch (Exception e) {
            return null;
        }
    }

    public static String hash(File file, String algorithm) {
        try (InputStream in = new FileInputStream(file)) {
            return hash(in, algorithm);
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

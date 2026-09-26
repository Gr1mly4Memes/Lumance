/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/** File hashing for manifests and verification (Mojang uses SHA-1, the index uses SHA-256). */
public final class Hashes {
    private Hashes() {
    }

    public static String sha1(File file) throws Exception {
        return hash(file, "SHA-1");
    }

    public static String sha256(File file) throws Exception {
        return hash(file, "SHA-256");
    }

    public static String hash(File file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = new FileInputStream(file);
                DigestInputStream din = new DigestInputStream(in, digest)) {
            din.readAllBytes();
        }
        return toHex(digest.digest());
    }

    public static String toHex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

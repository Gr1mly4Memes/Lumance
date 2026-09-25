/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.launcher.lumance.step;

import gr1mly4memes.launcher.lumance.util.Log;
import gr1mly4memes.launcher.lumance.util.Progress;
import gr1mly4memes.launcher.lumance.util.Sha1;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts the nested server jar and libraries out of the Mojang server bundle. */
public final class Bundle {
    private Bundle() {
    }

    /** One zip entry to extract: its path inside the bundle, hash and target file. */
    public record Entry(String entryName, String expectedHash, String algorithm, File target) {
    }

    /** Extracts a single zip entry, verifying its hash. Reuses the target when it already matches. */
    public static void extractEntry(File bundle, String entryName, String expectedHash, String algorithm, File target) throws Exception {
        try (ZipFile zip = new ZipFile(bundle)) {
            if (extractFromZip(zip, bundle.getName(), entryName, expectedHash, algorithm, target)) {
                Log.info("Extracted " + entryName);
            }
        }
    }

    /**
     * Extracts a batch of entries with a single progress line instead of one log
     * line per file. Opens the bundle once; entries that already verify are reused.
     */
    public static void extractAll(File bundle, List<Entry> entries, String label, String noun) throws Exception {
        Progress progress = new Progress(label, entries.size());
        int fresh = 0;
        try (ZipFile zip = new ZipFile(bundle)) {
            for (Entry entry : entries) {
                if (extractFromZip(zip, bundle.getName(), entry.entryName(), entry.expectedHash(), entry.algorithm(), entry.target())) {
                    fresh++;
                }
                progress.step();
            }
        }
        String nounCap = noun.isEmpty() ? noun : Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
        if (fresh == 0) {
            progress.finish(nounCap + " up to date (" + entries.size() + " verified)");
        } else {
            progress.finish(nounCap + " ready (" + entries.size() + " verified, " + fresh + " extracted)");
        }
    }

    /**
     * Extracts one entry from an already-open bundle. Returns true when the file
     * was (re)written, false when the existing target already verified.
     */
    private static boolean extractFromZip(ZipFile zip, String bundleName, String entryName, String expectedHash, String algorithm, File target) throws Exception {
        if (target.exists() && !expectedHash.isEmpty() && Objects.equals(Sha1.hash(target, algorithm), expectedHash)) {
            return false;
        }
        target.getParentFile().mkdirs();
        File part = new File(target.getParentFile(), target.getName() + ".part");
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            throw new IllegalStateException("Bundle " + bundleName + " has no entry " + entryName);
        }
        try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(part)) {
            in.transferTo(out);
        }
        if (!expectedHash.isEmpty() && !Objects.equals(Sha1.hash(part, algorithm), expectedHash)) {
            part.delete();
            throw new IllegalStateException("Hash mismatch extracting " + entryName);
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Could not replace " + target);
        }
        if (!part.renameTo(target)) {
            throw new IllegalStateException("Could not move " + part + " to " + target);
        }
        return true;
    }
}

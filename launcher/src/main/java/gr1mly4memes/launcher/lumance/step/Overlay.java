/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.launcher.lumance.step;

import gr1mly4memes.launcher.lumance.util.Log;
import gr1mly4memes.launcher.lumance.util.Sha1;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Applies the overlay patch embedded in the bootstrap jar onto the vanilla server jar,
 * producing the patched Lumance server jar. Deleted entries are skipped, changed and added
 * entries replace the vanilla ones.
 */
public final class Overlay {
    private Overlay() {
    }

    /** Fixed timestamp for overlaid entries so patched jars are byte-reproducible. */
    static final long OVERLAY_ENTRY_TIME = 0L;

    static ZipEntry copyOf(ZipEntry entry) throws Exception {
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setTime(entry.getTime());
        copy.setMethod(entry.getMethod());
        if (entry.getMethod() == ZipEntry.STORED) {
            copy.setSize(entry.getSize());
            copy.setCrc(entry.getCrc());
        }
        var extra = entry.getExtra();
        if (extra != null) {
            copy.setExtra(extra);
        }
        return copy;
    }

    public static void apply(File ownJar, String overlayPrefix, File baseJar, String expectedPatchedSha1, File target) throws Exception {
        if (target.exists() && Objects.equals(Sha1.of(target), expectedPatchedSha1)) {
            Log.info("Patched server jar is up to date");
            return;
        }
        String prefix = overlayPrefix.endsWith("/") ? overlayPrefix : overlayPrefix + "/";
        Log.info("Applying Lumance patch ...");

        Map<String, byte[]> overlay = new HashMap<>();
        Set<String> deleted = new HashSet<>();
        try (ZipFile zip = new ZipFile(ownJar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(prefix) || entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().substring(prefix.length());
                if (name.equals("deleted.list") || name.equals("overlay.properties")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    overlay.put(name, in.readAllBytes());
                }
            }
            ZipEntry deletedEntry = zip.getEntry(prefix + "deleted.list");
            if (deletedEntry != null) {
                try (InputStream in = zip.getInputStream(deletedEntry)) {
                    String list = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    for (String line : list.split("\n")) {
                        String name = line.trim();
                        if (!name.isEmpty()) {
                            deleted.add(name);
                        }
                    }
                }
            }
        }
        if (overlay.isEmpty() && deleted.isEmpty()) {
            throw new IllegalStateException("No overlay patch found in " + ownJar.getName() + " (expected " + prefix + " entries)");
        }

        target.getParentFile().mkdirs();
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try (ZipFile base = new ZipFile(baseJar); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(part))) {
            // Entries are copied with their original timestamps and methods so the patched
            // jar is reproducible: the same inputs always produce the same bytes.
            out.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
            Enumeration<? extends ZipEntry> entries = base.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || deleted.contains(entry.getName()) || overlay.containsKey(entry.getName())) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = base.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                out.putNextEntry(copyOf(entry));
                out.write(bytes);
                out.closeEntry();
            }
            List<String> names = new ArrayList<>(overlay.keySet());
            java.util.Collections.sort(names);
            for (String name : names) {
                ZipEntry fresh = new ZipEntry(name);
                fresh.setTime(OVERLAY_ENTRY_TIME);
                out.putNextEntry(fresh);
                out.write(overlay.get(name));
                out.closeEntry();
            }
        }
        if (!Objects.equals(Sha1.of(part), expectedPatchedSha1)) {
            part.delete();
            throw new IllegalStateException("Patched server jar failed verification - delete the cache folder and try again");
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Could not replace " + target);
        }
        if (!part.renameTo(target)) {
            throw new IllegalStateException("Could not move " + part + " to " + target);
        }
        Log.info("Patched server jar ready (" + overlay.size() + " overlaid files, " + deleted.size() + " removed)");
    }

    /** The jar (or classes dir) this class was loaded from. */
    public static File ownJar(Class<?> anchor) throws Exception {
        URI location = anchor.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path path = Path.of(location);
        if (!path.toString().endsWith(".jar")) {
            throw new IllegalStateException("Lumance must be started with java -jar lumance-*-server.jar");
        }
        return path.toFile();
    }
}

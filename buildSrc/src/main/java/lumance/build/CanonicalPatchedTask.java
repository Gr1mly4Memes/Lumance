/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Rebuilds the patched server jar with the exact launcher algorithm: vanilla
 * base entries (minus deletions and overlaid files) plus the sorted overlay
 * files with fixed timestamps. This is what the bootstrap manifest hashes,
 * so build and launcher can never disagree on the patched bytes.
 */
public abstract class CanonicalPatchedTask extends DefaultTask {
    /** Fixed timestamp for overlaid entries so patched jars are byte-reproducible. */
    static final long OVERLAY_ENTRY_TIME = 0L;

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getBaseJar();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getOverlayDir();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @TaskAction
    public void run() throws Exception {
        File baseJar = getBaseJar().getAsFile().get();
        File overlayDir = getOverlayDir().getAsFile().get();
        File outputJar = getOutputJar().getAsFile().get();

        Map<String, byte[]> overlay = new HashMap<>();
        Set<String> deleted = new HashSet<>();
        collectOverlay(overlayDir, overlayDir, overlay);
        File deletedList = new File(overlayDir, "deleted.list");
        if (deletedList.isFile()) {
            String list = new String(java.nio.file.Files.readAllBytes(deletedList.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String line : list.split("\n")) {
                String name = line.trim();
                if (!name.isEmpty()) {
                    deleted.add(name);
                }
            }
        }

        outputJar.getParentFile().mkdirs();
        File part = new File(outputJar.getParentFile(), outputJar.getName() + ".part");
        try (ZipFile base = new ZipFile(baseJar); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(part))) {
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
        if (outputJar.exists() && !outputJar.delete()) {
            throw new IllegalStateException("Could not replace " + outputJar);
        }
        if (!part.renameTo(outputJar)) {
            throw new IllegalStateException("Could not move " + part + " to " + outputJar);
        }
        getLogger().lifecycle("[Lumance] Canonical patched jar ready ({} overlaid files, {} removed)",
                overlay.size(), deleted.size());
    }

    private static void collectOverlay(File root, File dir, Map<String, byte[]> overlay) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectOverlay(root, file, overlay);
            } else if (file.isFile()) {
                String rel = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
                if (rel.equals("deleted.list") || rel.equals("overlay.properties")) {
                    continue;
                }
                overlay.put(rel, java.nio.file.Files.readAllBytes(file.toPath()));
            }
        }
    }

    static ZipEntry copyOf(ZipEntry entry) throws Exception {
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setTime(entry.getTime());
        copy.setMethod(entry.getMethod());
        if (entry.getMethod() == ZipEntry.STORED) {
            copy.setSize(entry.getSize());
            copy.setCrc(entry.getCrc());
        }
        byte[] extra = entry.getExtra();
        if (extra != null) {
            copy.setExtra(extra);
        }
        return copy;
    }
}

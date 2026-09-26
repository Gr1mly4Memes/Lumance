/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Diffs the vanilla base jar against the repacked server jar into the runtime
 * overlay: changed/added entries as files, removed entries in deleted.list.
 * The launcher applies this exact layout at runtime onto the downloaded
 * vanilla jar.
 */
public abstract class OverlayDiffTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getBaseJar();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getPatchedJar();

    @OutputDirectory
    public abstract DirectoryProperty getOverlayDir();

    @TaskAction
    public void run() throws Exception {
        File baseJar = getBaseJar().getAsFile().get();
        File patchedJar = getPatchedJar().getAsFile().get();
        File overlayDir = getOverlayDir().getAsFile().get();

        Map<String, byte[]> baseEntries = readFiles(baseJar);
        Map<String, byte[]> patchedEntries = readFiles(patchedJar);

        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : patchedEntries.entrySet()) {
            byte[] baseBytes = baseEntries.get(entry.getKey());
            if (baseBytes == null || !java.util.Arrays.equals(baseBytes, entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        List<String> deleted = new ArrayList<>();
        for (String name : baseEntries.keySet()) {
            if (!patchedEntries.containsKey(name)) {
                deleted.add(name);
            }
        }
        java.util.Collections.sort(changed);
        java.util.Collections.sort(deleted);

        deleteRecursively(overlayDir);
        for (String name : changed) {
            File target = new File(overlayDir, name.replace('/', File.separatorChar));
            target.getParentFile().mkdirs();
            writeBytes(target, patchedEntries.get(name));
        }
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(new File(overlayDir, "deleted.list")), StandardCharsets.UTF_8)) {
            for (String name : deleted) {
                writer.write(name + "\n");
            }
        }
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(new File(overlayDir, "overlay.properties")), StandardCharsets.UTF_8)) {
            writer.write("overlay.base.sha1=" + Hashes.sha1(baseJar) + "\n");
            writer.write("overlay.patched.sha1=" + Hashes.sha1(patchedJar) + "\n");
        }
        getLogger().lifecycle("[Lumance] Overlay: {} overlaid files, {} removed", changed.size(), deleted.size());
    }

    private static Map<String, byte[]> readFiles(File jar) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> zipEntries = zip.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry entry = zipEntries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static void writeBytes(File target, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
    }

    private static void deleteRecursively(File file) throws Exception {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            throw new IllegalStateException("Could not delete " + file);
        }
    }
}

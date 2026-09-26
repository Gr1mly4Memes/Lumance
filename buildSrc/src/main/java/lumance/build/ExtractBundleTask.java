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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Extracts the nested server jar and the game libraries out of the vanilla
 * bundle. Library entries are recorded (SHA-256 + size) in the libs index the
 * bootstrap manifest is built from.
 */
public abstract class ExtractBundleTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getBundle();

    @OutputFile
    public abstract RegularFileProperty getServerJar();

    @OutputDirectory
    public abstract DirectoryProperty getLibsDir();

    @OutputFile
    public abstract RegularFileProperty getExtractedProps();

    @OutputFile
    public abstract RegularFileProperty getLibsIndex();

    @TaskAction
    public void run() throws Exception {
        File bundle = getBundle().getAsFile().get();
        String serverEntry = null;
        try (ZipFile zip = new ZipFile(bundle)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("META-INF/versions/") && name.endsWith(".jar") && name.contains("/server-")) {
                    serverEntry = name;
                    break;
                }
            }
        }
        if (serverEntry == null) {
            throw new IllegalStateException("No nested server jar found in " + bundle.getName());
        }
        getLogger().lifecycle("[Lumance] Extracting nested server jar {} ...", serverEntry);
        File serverJar = getServerJar().getAsFile().get();
        copyEntry(bundle, serverEntry, serverJar);

        File libsDir = getLibsDir().getAsFile().get();
        List<String> index = new ArrayList<>();
        int count = 0;
        try (ZipFile zip = new ZipFile(bundle)) {
            List<ZipEntry> libs = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith("META-INF/libraries/")) {
                    libs.add(entry);
                }
            }
            libs.sort((a, b) -> a.getName().compareTo(b.getName()));
            for (ZipEntry entry : libs) {
                String rel = entry.getName().substring("META-INF/libraries/".length());
                File target = new File(libsDir, rel.replace('/', File.separatorChar));
                copyEntry(zip, entry, target);
                index.add(rel + "\t" + Hashes.sha256(target) + "\t" + target.length());
                count++;
            }
        }
        getLogger().lifecycle("[Lumance] Extracted {} libraries", count);

        File extractedProps = getExtractedProps().getAsFile().get();
        extractedProps.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(extractedProps), StandardCharsets.UTF_8)) {
            writer.write("server.entry=" + serverEntry + "\n");
            writer.write("server.sha1=" + Hashes.sha1(serverJar) + "\n");
        }
        File libsIndex = getLibsIndex().getAsFile().get();
        libsIndex.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(libsIndex), StandardCharsets.UTF_8)) {
            for (String line : index) {
                writer.write(line + "\n");
            }
        }
    }

    private static void copyEntry(File bundle, String entryName, File target) throws Exception {
        try (ZipFile zip = new ZipFile(bundle)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("Bundle " + bundle.getName() + " has no entry " + entryName);
            }
            copyEntry(zip, entry, target);
        }
    }

    private static void copyEntry(ZipFile zip, ZipEntry entry, File target) throws Exception {
        target.getParentFile().mkdirs();
        try (InputStream in = zip.getInputStream(entry); FileOutputStream out = new FileOutputStream(target)) {
            in.transferTo(out);
        }
    }
}

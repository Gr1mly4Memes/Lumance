/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Builds a patched game jar: the vanilla base jar with the compiled classes
 * overlaid (replacing same-named entries), stamped with the Lumance version.
 */
public abstract class RepackTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getBaseJar();

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassesDirs();

    @Input
    public abstract Property<String> getLumanceVersion();

    @Input
    public abstract Property<String> getMcVersion();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @TaskAction
    public void run() throws Exception {
        File baseJar = getBaseJar().getAsFile().get();
        File outputJar = getOutputJar().getAsFile().get();

        List<Path> classFiles = new ArrayList<>();
        for (File dir : getClassesDirs().getFiles()) {
            if (!dir.isDirectory()) {
                continue;
            }
            final Path root = dir.toPath();
            try (var stream = Files.walk(root)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(".class")) {
                        classFiles.add(file);
                    }
                }
            }
        }
        java.util.Map<String, Path> overlay = new java.util.TreeMap<>();
        for (Path file : classFiles) {
            for (File dir : getClassesDirs().getFiles()) {
                if (!dir.isDirectory()) {
                    continue;
                }
                final Path root = dir.toPath();
                if (file.startsWith(root)) {
                    overlay.put(root.relativize(file).toString().replace(File.separatorChar, '/'), file);
                    break;
                }
            }
        }

        outputJar.getParentFile().mkdirs();
        File part = new File(outputJar.getParentFile(), outputJar.getName() + ".part");
        try (ZipFile base = new ZipFile(baseJar);
                ZipOutputStream out = new ZipOutputStream(new FileOutputStream(part))) {
            out.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
            Enumeration<? extends ZipEntry> entries = base.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || overlay.containsKey(entry.getName())) {
                    continue;
                }
                // Mojang signs game jars (MOJANGCS.SF/.RSA). Any modification -
                // overlaid classes, the stamped manifest below - invalidates
                // those signatures, and the JVM then refuses to load EVERY
                // class (SecurityException). Like any fork, strip them.
                if (isSignatureFile(entry.getName())) {
                    continue;
                }
                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    out.write(stampedManifest(base).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.closeEntry();
                    continue;
                }
                out.putNextEntry(copyOf(entry));
                try (InputStream in = base.getInputStream(entry)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
            for (var overlaid : overlay.entrySet()) {
                out.putNextEntry(new ZipEntry(overlaid.getKey()));
                try (InputStream in = new FileInputStream(overlaid.getValue().toFile())) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
        if (outputJar.exists() && !outputJar.delete()) {
            throw new IllegalStateException("Could not replace " + outputJar);
        }
        if (!part.renameTo(outputJar)) {
            throw new IllegalStateException("Could not move " + part + " to " + outputJar);
        }
        getLogger().lifecycle("[Lumance] Repacked {} ({} overlaid classes)", outputJar.getName(), overlay.size());
    }

    private String stampedManifest(ZipFile base) throws Exception {
        ZipEntry manifestEntry = base.getEntry("META-INF/MANIFEST.MF");
        Manifest manifest = new Manifest();
        if (manifestEntry != null) {
            try (InputStream in = base.getInputStream(manifestEntry)) {
                manifest.read(in);
            }
        }
        Attributes main = manifest.getMainAttributes();
        if (!main.containsKey(Attributes.Name.MANIFEST_VERSION)) {
            main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }
        main.putValue("Implementation-Title", "Lumance");
        main.putValue("Implementation-Version", getLumanceVersion().get() + "+mc" + getMcVersion().get());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        manifest.write(bytes);
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ZipEntry copyOf(ZipEntry entry) throws Exception {
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

    /** Mojang signature files (MOJANGCS.SF/.RSA and kin). */
    static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }
}

/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Writes the bootstrap manifest (lumance.properties) that ships inside the server jar and
 * tells the launcher exactly what to download, verify and patch at runtime.
 */
public abstract class WriteBootstrapManifestTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getVanillaProps();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getExtractedProps();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getLibsIndex();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getBaseJar();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getPatchedJar();

    @Input
    public abstract Property<String> getFabricCoordinates();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getFabricLibsDir();

    @Input
    public abstract Property<String> getBundledModCoordinates();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getBundledModsDir();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getModJar();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getLog4jConfig();

    @Input
    public abstract Property<String> getLumanceVersion();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    @TaskAction
    public void run() throws Exception {
        Properties vanilla = load(getVanillaProps().getAsFile().get());
        Properties extracted = load(getExtractedProps().getAsFile().get());
        File baseJar = getBaseJar().getAsFile().get();
        File patchedJar = getPatchedJar().getAsFile().get();

        TreeMap<String, String> libs = new TreeMap<>();
        for (String line : new String(java.nio.file.Files.readAllBytes(getLibsIndex().getAsFile().get().toPath()), StandardCharsets.UTF_8).split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length >= 2 && !parts[0].isEmpty()) {
                String size = parts.length >= 3 ? parts[2] : "";
                libs.put(parts[0], parts[1] + "\t" + size);
            }
        }

        StringBuilder manifest = new StringBuilder();
        manifest.append("lumance.version=").append(getLumanceVersion().get()).append('\n');
        manifest.append("minecraft.version=").append(vanilla.getProperty("minecraft.version")).append('\n');
        manifest.append("main.class=net.minecraft.server.Main\n");
        manifest.append("bundle.url=").append(vanilla.getProperty("bundle.url")).append('\n');
        manifest.append("bundle.sha1=").append(vanilla.getProperty("bundle.sha1")).append('\n');
        manifest.append("bundle.size=").append(vanilla.getProperty("bundle.size")).append('\n');
        manifest.append("server.entry=").append(extracted.getProperty("server.entry")).append('\n');
        manifest.append("server.sha1=").append(extracted.getProperty("server.sha1")).append('\n');
        manifest.append("overlay.dir=lumance/overlay\n");
        manifest.append("overlay.base.sha1=").append(Hashes.sha1(baseJar)).append('\n');
        manifest.append("overlay.patched.sha1=").append(Hashes.sha1(patchedJar)).append('\n');
        int i = 0;
        for (var entry : libs.entrySet()) {
            // Brigadier ships as editable compiled sources inside the patched jar,
            // so its binary is never deployed to the runtime classpath.
            if (entry.getKey().contains("/brigadier/")) {
                continue;
            }
            manifest.append("lib.").append(i).append(".path=").append(entry.getKey()).append('\n');
            String[] parts = entry.getValue().split("\t");
            manifest.append("lib.").append(i).append(".sha256=").append(parts[0]).append('\n');
            manifest.append("lib.").append(i).append(".size=").append(parts.length > 1 ? parts[1] : "").append('\n');
            i++;
        }
        manifest.append("lib.count=").append(i).append('\n');

        manifest.append("fabric.repo=https://maven.fabricmc.net/\n");
        int f = 0;
        String loaderVersion = "";
        for (String coord : getFabricCoordinates().get().split(",")) {
            String[] parts = coord.split(":");
            if (parts.length != 3) {
                continue;
            }
            if (parts[0].equals("net.fabricmc") && parts[1].equals("fabric-loader")) {
                loaderVersion = parts[2];
            }
            String mavenPath = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/";
            File jar = stagedJar(getFabricLibsDir().getAsFile().get(), parts[1], parts[2]);
            if (jar == null) {
                throw new IllegalStateException("Fabric library not staged: " + coord);
            }
            manifest.append("fabric.").append(f).append(".name=").append(parts[0]).append(':').append(parts[1]).append('\n');
            manifest.append("fabric.").append(f).append(".version=").append(parts[2]).append('\n');
            manifest.append("fabric.").append(f).append(".path=").append(mavenPath).append(jar.getName()).append('\n');
            manifest.append("fabric.").append(f).append(".sha256=").append(Hashes.sha256(jar)).append('\n');
            manifest.append("fabric.").append(f).append(".size=").append(jar.length()).append('\n');
            f++;
        }
        manifest.append("fabric.count=").append(f).append('\n');
        manifest.append("fabric.loader.version=").append(loaderVersion).append('\n');

        // Mods bundled with Lumance (e.g. Fabric API). Loaded as mods via
        // fabric.addMods, never placed on the game classpath.
        int b = 0;
        for (String coord : getBundledModCoordinates().get().split(",")) {
            String[] parts = coord.split(":");
            if (parts.length != 3) {
                continue;
            }
            String mavenPath = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/";
            File jar = stagedJar(getBundledModsDir().getAsFile().get(), parts[1], parts[2]);
            if (jar == null) {
                throw new IllegalStateException("Bundled mod not staged: " + coord);
            }
            manifest.append("bundledmod.").append(b).append(".name=").append(parts[0]).append(':').append(parts[1]).append('\n');
            manifest.append("bundledmod.").append(b).append(".version=").append(parts[2]).append('\n');
            manifest.append("bundledmod.").append(b).append(".path=").append(mavenPath).append(jar.getName()).append('\n');
            manifest.append("bundledmod.").append(b).append(".sha256=").append(Hashes.sha256(jar)).append('\n');
            manifest.append("bundledmod.").append(b).append(".size=").append(jar.length()).append('\n');
            b++;
        }
        manifest.append("bundledmod.count=").append(b).append('\n');

        File modJar = getModJar().getAsFile().get();
        manifest.append("lumance.mod.file=lumance/mod.jar\n");
        manifest.append("lumance.mod.sha256=").append(Hashes.sha256(modJar)).append('\n');
        manifest.append("lumance.mod.size=").append(modJar.length()).append('\n');

        File log4jConfig = getLog4jConfig().getAsFile().get();
        manifest.append("lumance.config.file=lumance/log4j2.xml\n");
        manifest.append("lumance.config.sha256=").append(Hashes.sha256(log4jConfig)).append('\n');

        File output = getOutput().getAsFile().get();
        output.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8)) {
            writer.write(manifest.toString());
        }
        getLogger().lifecycle("[Lumance] Bootstrap manifest: {} libs, patched sha1 {}", i, Hashes.sha1(patchedJar));
    }

    private static Properties load(File file) throws Exception {
        Properties props = new Properties();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    private static File stagedJar(File dir, String artifact, String version) {
        File[] candidates = dir.listFiles((parent, name) -> name.startsWith(artifact + "-") && name.endsWith(".jar"));
        if (candidates != null) {
            for (File candidate : candidates) {
                if (candidate.getName().equals(artifact + "-" + version + ".jar")) {
                    return candidate;
                }
            }
        }
        return null;
    }
}

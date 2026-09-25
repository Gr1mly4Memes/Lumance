/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.launcher.lumance;

import gr1mly4memes.launcher.lumance.manifest.BootstrapManifest;
import gr1mly4memes.launcher.lumance.step.Bundle;
import gr1mly4memes.launcher.lumance.step.Download;
import gr1mly4memes.launcher.lumance.step.Overlay;
import gr1mly4memes.launcher.lumance.step.ServerLaunch;
import gr1mly4memes.launcher.lumance.util.Log;
import gr1mly4memes.launcher.lumance.util.Sha1;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lumance bootstrap (paperclip-style). On first run it downloads the pinned vanilla server
 * bundle from Mojang, extracts it, applies the embedded Lumance overlay patch and launches
 * the patched server through Fabric Loader. Everything is cached and hash-verified, so later
 * starts are instant and a corrupt file is re-fetched instead of crashing the server tick loop.
 *
 * <p>Lumance itself ships as a built-in Fabric mod ({@code src/main/java}): its entrypoint
 * and mixins are always loaded via {@code fabric.addMods}, together with the bundled
 * Fabric API and whatever the user drops into {@code mods/}. Drop extra mod jars into
 * {@code mods/} like a stock Fabric server - anything needing Fabric API just works.
 * Mods must be built for this exact game version.
 *
 * <p>Usage: {@code java -jar lumance-<mc>-server.jar [server args, e.g. nogui]}
 */
public class Main {
    public static void main(String[] args) throws Exception {
        BootstrapManifest manifest = BootstrapManifest.load();
        Log.info("Lumance " + manifest.lumanceVersion() + " for Minecraft " + manifest.minecraftVersion());

        File cacheDir = new File("cache");
        File librariesDir = new File("libraries");
        cacheDir.mkdirs();
        librariesDir.mkdirs();

        // 1. Vanilla bundle from Mojang (pinned by SHA-1 in the manifest).
        File bundle = new File(cacheDir, "bundle-" + Sha1.shortId(manifest.bundleSha1()) + ".jar");
        Download.ensureAll(List.of(new Download.Entry(manifest.bundleUrl(), manifest.bundleSha1(), "SHA-1", bundle)),
                "Downloading bundle", "bundle");

        // 2. Nested server jar + libraries out of the bundle.
        File serverJar = new File(cacheDir, "server-" + Sha1.shortId(manifest.serverSha1()) + ".jar");
        Bundle.extractEntry(bundle, manifest.serverEntry(), manifest.serverSha1(), "SHA-1", serverJar);
        List<Bundle.Entry> libraries = new ArrayList<>();
        for (BootstrapManifest.Lib lib : manifest.libs()) {
            libraries.add(new Bundle.Entry("META-INF/libraries/" + lib.path(), lib.sha256(), "SHA-256",
                    new File(librariesDir, lib.path().replace('/', File.separatorChar))));
        }
        Bundle.extractAll(bundle, libraries, "Extracting libraries", "libraries");

        // 3. Lumance overlay patch -> patched server jar.
        File ownJar = Overlay.ownJar(Main.class);
        File patchedJar = new File(cacheDir, "lumance-patched-" + Sha1.shortId(manifest.overlayPatchedSha1()) + ".jar");
        Overlay.apply(ownJar, manifest.overlayDir(), serverJar, manifest.overlayPatchedSha1(), patchedJar);

        // 4. Built-in mods: Lumance itself (embedded) + bundled mods like Fabric API
        // (downloaded once, hash-verified). Mod jars live in cache/ - never on the
        // game classpath - and are handed to Knot via fabric.addMods.
        File modJar = new File(cacheDir, "lumance-mod-" + Sha1.shortId(manifest.modSha256()) + ".jar");
        extractEmbedded(ownJar, manifest.modFile(), manifest.modSha256(), modJar);
        List<String> addMods = new ArrayList<>();
        addMods.add(modJar.getAbsolutePath());
        List<String> bundledNames = new ArrayList<>();
        List<Download.Entry> bundledFiles = new ArrayList<>();
        for (BootstrapManifest.Lib bundled : manifest.bundledMods()) {
            String fileName = bundled.path().substring(bundled.path().lastIndexOf('/') + 1);
            File target = new File(cacheDir, fileName);
            bundledFiles.add(new Download.Entry(manifest.fabricRepo() + bundled.path(), bundled.sha256(), "SHA-256", target));
            addMods.add(target.getAbsolutePath());
            bundledNames.add(fileName.replaceFirst("\\.jar$", ""));
        }
        Download.ensureAll(bundledFiles, "Downloading bundled mods", "bundled mods");

        // 5. Console logging config from inside the bootstrap (hash-verified).
        // This overrides Mojang's config inside the logging jar; theirs stays untouched.
        File logConfig = new File(cacheDir, "lumance-log4j2-" + Sha1.shortId(manifest.configSha256()) + ".xml");
        extractEmbedded(ownJar, manifest.configFile(), manifest.configSha256(), logConfig);

        // 6. Fabric Loader stack (pinned + hash-verified, same as the build).
        List<String> fabricHead = new ArrayList<>();
        List<Download.Entry> fabricFiles = new ArrayList<>();
        for (BootstrapManifest.Lib lib : manifest.fabricLibs()) {
            File target = new File(librariesDir, lib.path().replace('/', File.separatorChar));
            fabricFiles.add(new Download.Entry(manifest.fabricRepo() + lib.path(), lib.sha256(), "SHA-256", target));
            fabricHead.add(target.getAbsolutePath());
        }
        Download.ensureAll(fabricFiles, "Downloading Fabric libraries", "Fabric libraries");
        // Patched jar + game libs. Fabric jars already downloaded above live under
        // libraries/ too, so de-duplicate to keep them at the head of the classpath.
        List<String> classPath = new ArrayList<>(fabricHead);
        for (String entry : ServerLaunch.serverClasspath(patchedJar, librariesDir)) {
            if (!classPath.contains(entry)) {
                classPath.add(entry);
            }
        }

        List<String> userMods = findMods(new File("mods"));
        List<String> builtIn = new ArrayList<>();
        builtIn.add("lumance");
        builtIn.addAll(bundledNames);
        Log.info("Fabric Loader " + manifest.fabricLoaderVersion() + " with built-in "
                + String.join(" + ", builtIn) + " + " + userMods.size() + " mod file(s) in mods/");
        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-Dfabric.addMods=" + String.join(File.pathSeparator, addMods));
        jvmArgs.add("-Dlog4j.configurationFile=" + logConfig.getAbsolutePath());
        jvmArgs.add("-Dlog4j.skipJansi=true");
        // JLine FFM/JNI plus the pre-existing JNA warnings go quiet; Paper does the same.
        jvmArgs.add("--enable-native-access=ALL-UNNAMED");
        ServerLaunch.launch(classPath, jvmArgs, "net.fabricmc.loader.impl.launch.knot.KnotServer", args);
    }

    /** Copies one entry out of the bootstrap jar, verifying its SHA-256. */
    static void extractEmbedded(File ownJar, String entry, String expectedSha256, File target) throws Exception {
        if (target.exists() && Objects.equals(Sha1.sha256(target), expectedSha256)) {
            return;
        }
        target.getParentFile().mkdirs();
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try (ZipFile zip = new ZipFile(ownJar)) {
            ZipEntry zipEntry = zip.getEntry(entry);
            if (zipEntry == null) {
                throw new IllegalStateException("Embedded " + entry + " is missing from " + ownJar.getName());
            }
            try (InputStream in = zip.getInputStream(zipEntry);
                    OutputStream out = new FileOutputStream(part)) {
                in.transferTo(out);
            }
        }
        if (!Objects.equals(Sha1.sha256(part), expectedSha256)) {
            part.delete();
            throw new IllegalStateException("Embedded mod jar failed verification - re-download the Lumance jar");
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Could not replace " + target);
        }
        if (!part.renameTo(target)) {
            throw new IllegalStateException("Could not move " + part + " to " + target);
        }
        Log.info("Extracted " + target.getName());
    }

    /** Jar files directly inside mods/ (what Knot itself scans). */
    static List<String> findMods(File modsDir) {
        List<String> mods = new ArrayList<>();
        File[] files = modsDir.isDirectory() ? modsDir.listFiles() : null;
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".jar")) {
                    mods.add(file.getName());
                }
            }
        }
        return mods;
    }
}

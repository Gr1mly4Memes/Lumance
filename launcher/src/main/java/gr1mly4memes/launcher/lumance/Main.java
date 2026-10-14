/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.launcher.lumance;

import gr1mly4memes.launcher.lumance.manifest.BootstrapManifest;
import gr1mly4memes.launcher.lumance.step.Bundle;
import gr1mly4memes.launcher.lumance.step.Download;
import gr1mly4memes.launcher.lumance.step.MixinTolerance;
import gr1mly4memes.launcher.lumance.step.Overlay;
import gr1mly4memes.launcher.lumance.step.ServerLaunch;
import gr1mly4memes.launcher.lumance.util.Log;
import gr1mly4memes.launcher.lumance.util.Progress;
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

        // Lumance - remember file hashes between boots. Without this the launcher re-reads the
        // bundle, every library and every mod jar on every start just to confirm nothing changed.
        gr1mly4memes.launcher.lumance.util.Sha1.useCache(cacheDir);
        File librariesDir = new File("libraries");
        cacheDir.mkdirs();
        librariesDir.mkdirs();

        // Lumance start - per-step boot timing.
        //
        // Every one of the steps below is a cache hit on all but the first run, so "why is startup
        // slow" is otherwise unanswerable from the outside: the log shows which step ran, not how
        // long it took. This records the cost of each step and prints them in order, so a regression
        // in the overlay patch (the one step that rewrites ~20k zip entries) is visible instead of
        // guessed at.
        // Lumance end

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
        // Lumance - the console logging config and the Bukkit/Paper runtime libraries
        // (jline, snakeyaml, bungeecord-chat, commons-lang, the Maven resolver) all ride inside the
        // bootstrap jar under lumance/lib/. Mojang's bundle does not ship them and the server
        // classpath is built from libraries/ only, so extract the embedded copies alongside the
        // rest. One batch = one progress line instead of 30-odd "Extracted ..." lines.
        File lumanceLibsDir = new File(librariesDir, "lumance");
        lumanceLibsDir.mkdirs();
        List<EmbeddedEntry> embedded = new ArrayList<>();
        embedded.add(new EmbeddedEntry(manifest.configFile(), manifest.configSha256(), logConfig));
        for (BootstrapManifest.Lib lib : manifest.bootstrapLibs()) {
            String fileName = lib.path().substring(lib.path().lastIndexOf('/') + 1);
            embedded.add(new EmbeddedEntry(lib.path(), lib.sha256(), new File(lumanceLibsDir, fileName)));
        }
        extractEmbeddedAll(ownJar, embedded, "Extracting runtime files", "runtime files");

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

        // Lumance - soften mixin failure handling for every mod in mods/.
        //
        // A mod anchors its mixins on exact descriptors and lexical positions in vanilla methods,
        // so any CraftBukkit change to a signature, an LVT, or a call site breaks that anchor.
        // Those thresholds live in the mod's own bytecode and cannot be repaired server-side, but
        // whether one broken anchor kills the process is under our control: Fabric reads
        // "required" and "injectors.defaultRequire" from each mod's mixin config, and lowering the
        // required count turns a fatal InjectionError into a skipped injection plus a warning.
        //
        // The mods are copied into a shadow folder and Fabric is pointed at that instead, so the
        // operator's jars are never modified. Mods that are already tolerant are copied byte-for-byte.
        MixinTolerance.Result tolerance = MixinTolerance.apply(new File("mods"), cacheDir,
                !"false".equalsIgnoreCase(System.getProperty("lumance.mixinTolerance")));
        if (tolerance != null) {
            jvmArgs.add("-Dfabric.modsFolder=" + tolerance.folder());
            if (tolerance.configsRewritten() > 0) {
                Log.info("Mixin tolerance active (" + tolerance.mods() + " mod(s), "
                        + tolerance.configsRewritten() + " config(s) relaxed): failed injections now"
                        + " warn instead of killing the server");
                Log.error("NOTE: a relaxed mixin is DEGRADED, not fixed - that mod runs with less of"
                        + " its code active. Watch for \"Mixin apply for mod ... failed\" in the log."
                        + " Pass -Dlumance.mixinTolerance=false to restore fail-fast behaviour.");
            } else {
                Log.info("Mixin tolerance active (" + tolerance.mods() + " mod(s)): no mod needed"
                        + " relaxing, all already tolerant");
            }
        }
        jvmArgs.add("-Dfabric.addMods=" + String.join(File.pathSeparator, addMods));
        // Lumance - expose JLine to the game classloader so the coloured console appender can be
        // built. Knot isolates the game from the launch classpath: a class resolved from a parent
        // classloader is only accepted when its code source is a declared "system library",
        // otherwise loadClass fails with "can't load class ... as it hasn't been exposed to the
        // game". The log4j2 config below instantiates
        // net.minecrell.terminalconsole.TerminalConsoleAppender, whose factory method signature
        // mentions org.jline.terminal.Terminal and org.jline.reader.LineReader, so Log4j must
        // resolve those types *while building its configuration* - before any Minecraft code runs.
        //
        // Only JLine belongs on this list. terminalconsoleappender must NOT: declaring a jar as a
        // system library moves it off Fabric's game loader, and log4j finds its plugins by
        // reading META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat via
        // ClassLoader.getResources(). Adding it here makes that descriptor disappear and the
        // server silently drops back to uncoloured logging with
        // "No appender named TerminalConsole was configured". The two requirements pull in
        // opposite directions - JLine needs parent access, the appender needs game-side visibility -
        // so they have to be satisfied by different jars.
        List<String> consoleLibs = consoleLibraries(lumanceLibsDir);
        if (!consoleLibs.isEmpty()) {
            jvmArgs.add("-Dfabric.systemLibraries=" + String.join(File.pathSeparator, consoleLibs));
        }
        jvmArgs.add("-Dlog4j.configurationFile=" + logConfig.getAbsolutePath());
        jvmArgs.add("-Dlog4j.skipJansi=true");
        // JLine FFM/JNI plus the pre-existing JNA warnings go quiet; Paper does the same.
        jvmArgs.add("--enable-native-access=ALL-UNNAMED");
        // Lumance start - GC tuning. Pure JVM flags: nothing here is visible to a Fabric mod or a
        // Bukkit plugin, so it cannot break either - it only changes how the collector behaves.
        // Skipped entirely when the operator already supplied their own -XX: flag or turned tuning
        // off with -Dlumance.gcTuning=false, so nothing is ever overridden twice.
        jvmArgs.addAll(GcTuning.heapArgs(List.of(args)));
        if (!"false".equalsIgnoreCase(System.getProperty("lumance.gcTuning"))) {
            jvmArgs.addAll(GcTuning.jvmArgs(List.of(args), jvmArgs));
        }
        // Lumance end
        // Lumance - persist what we hashed, so the next boot can skip it.
        //
        // Written before the launch, not after: ServerLaunch.launch does not return until the
        // server stops, so flushing afterwards would leave the cache missing for the whole
        // lifetime of a running server - exactly when a restart is most likely.
        gr1mly4memes.launcher.lumance.util.Sha1.flushCache();
        ServerLaunch.launch(classPath, jvmArgs, "net.fabricmc.loader.impl.launch.knot.KnotServer", args);
    }

    /**
     * The JLine modules in {@code libraries/lumance}, which the coloured terminal appender needs
     * resolved while log4j builds its configuration. Declaring them as Fabric system libraries is
     * what lets Knot hand them to the game classloader.
     *
     * <p>terminalconsoleappender is intentionally excluded - see the {@code fabric.systemLibraries}
     * comment at the call site for why it must stay on the game side.
     */
    static List<String> consoleLibraries(File lumanceLibsDir) {
        List<String> out = new ArrayList<>();
        File[] files = lumanceLibsDir.listFiles();
        if (files == null) {
            return out;
        }
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (name.startsWith("jline-")) {
                out.add(file.getAbsolutePath());
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** One embedded bootstrap entry to extract: path inside the jar, hash and target file. */
    record EmbeddedEntry(String entry, String expectedSha256, File target) {
    }

    /**
     * Extracts a batch of embedded entries behind a single progress line instead of one
     * log line per file, and opens the bootstrap jar once rather than once per entry
     * (there are 30+ runtime libraries, so that also saves 30+ ZipFile opens).
     * Returns how many files were actually (re)written; the rest already verified.
     */
    static int extractEmbeddedAll(File ownJar, List<EmbeddedEntry> entries, String label, String noun) throws Exception {
        Progress progress = new Progress(label, entries.size());
        int fresh = 0;
        try (ZipFile zip = new ZipFile(ownJar)) {
            for (EmbeddedEntry entry : entries) {
                if (writeEmbedded(zip, ownJar.getName(), entry.entry(), entry.expectedSha256(), entry.target())) {
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
        return fresh;
    }

    /** Copies one entry out of the bootstrap jar, verifying its SHA-256. Returns true if written. */
    static boolean extractEmbedded(File ownJar, String entry, String expectedSha256, File target) throws Exception {
        try (ZipFile zip = new ZipFile(ownJar)) {
            return writeEmbedded(zip, ownJar.getName(), entry, expectedSha256, target);
        }
    }

    /** Writes one entry from an already-open bootstrap jar. Returns true if the target was written. */
    private static boolean writeEmbedded(ZipFile zip, String jarName, String entry, String expectedSha256, File target) throws Exception {
        if (target.exists() && Objects.equals(Sha1.sha256(target), expectedSha256)) {
            return false;
        }
        target.getParentFile().mkdirs();
        File part = new File(target.getParentFile(), target.getName() + ".part");
        ZipEntry zipEntry = zip.getEntry(entry);
        if (zipEntry == null) {
            throw new IllegalStateException("Embedded " + entry + " is missing from " + jarName);
        }
        try (InputStream in = zip.getInputStream(zipEntry);
                OutputStream out = new FileOutputStream(part)) {
            in.transferTo(out);
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
        return true;
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

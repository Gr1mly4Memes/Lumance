/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.launcher.lumance.step;

import gr1mly4memes.launcher.lumance.util.Log;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Launches the patched server in a fresh JVM with a real {@code -classpath}, the same
 * way PaperMC's paperclip and NeoForge's run scripts do it.
 *
 * <p>The previous design loaded the server in-process through a child URLClassLoader.
 * That proved unreliable: with byte-identical jars, {@code -cp} boots every time while
 * the child loader intermittently loses visibility of classes mid-run
 * ({@code NoClassDefFoundError} for classes that resolve fine moments earlier).
 * A forked JVM behaves exactly like a vanilla install and sidesteps the issue entirely.
 */
public final class ServerLaunch {
    private ServerLaunch() {
    }

    /** Core classes that must be present in the patched jar / libraries, or we fail fast. */
    private static final String[] REQUIRED_PATCHED = {
            "net/minecraft/server/MinecraftServer.class",
            "net/minecraft/util/Crypt.class",
            "net/minecraft/util/ModCheck.class",
            "net/minecraft/world/scores/ScoreboardSaveData.class",
    };
    private static final String REQUIRED_LIB = "com/mojang/logging/LogQueues.class";

    public static void launch(File patchedJar, File librariesDir, String mainClass, String[] args) throws Exception {
        launch(serverClasspath(patchedJar, librariesDir), mainClass, args);
    }

    /** Patched jar first, then every dependency jar (no installer inputs or strays). */
    public static List<String> serverClasspath(File patchedJar, File librariesDir) throws Exception {
        List<String> classPath = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        add(classPath, seen, patchedJar);
        collectJars(librariesDir, librariesDir, classPath, seen);
        verifyJarContents(patchedJar, librariesDir);
        return classPath;
    }

    public static void launch(List<String> classPath, String mainClass, String[] args) throws Exception {
        launch(classPath, List.of(), mainClass, args);
    }

    public static void launch(List<String> classPath, List<String> jvmArgs, String mainClass, String[] args) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classPath));
        command.add(mainClass);
        command.addAll(List.of(args));

        Log.info("Starting Minecraft server ...");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.inheritIO();
        Process process = builder.start();
        int exit = process.waitFor();
        if (exit != 0) {
            System.exit(exit);
        }
    }

    /**
     * Fail fast when a core class the server needs is missing from the jars on disk,
     * instead of crashing deep in the server tick loop with a bare NoClassDefFoundError.
     */
    private static void verifyJarContents(File patchedJar, File librariesDir) throws Exception {
        try (ZipFile zip = new ZipFile(patchedJar)) {
            for (String resource : REQUIRED_PATCHED) {
                if (zip.getEntry(resource) == null) {
                    Log.error("Patched server jar is missing " + resource.replace('/', '.').replace(".class", "") + ".");
                    Log.error("Delete the cache folder and try again.");
                    System.exit(1);
                }
            }
        }
        boolean foundLogging = false;
        Deque<File> queue = new ArrayDeque<>();
        queue.add(librariesDir);
        outer:
        while (!queue.isEmpty()) {
            File dir = queue.removeFirst();
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file.isDirectory()) {
                    queue.add(file);
                } else if (file.getName().endsWith(".jar")) {
                    try (ZipFile zip = new ZipFile(file)) {
                        if (zip.getEntry(REQUIRED_LIB) != null) {
                            foundLogging = true;
                            break outer;
                        }
                    }
                }
            }
        }
        if (!foundLogging) {
            Log.error("Cannot find com.mojang.logging.LogQueues in libraries/ - a library is corrupt.");
            Log.error("Delete the libraries and cache folders and try again.");
            System.exit(1);
        }
    }

    /**
     * Collects dependency jars. Install intermediates must never reach the runtime classpath:
     * stray full-server jars (e.g. bundler output named minecraft*.jar) would shadow the
     * patched jar, and anything non-dependency is simply not needed.
     */
    private static void collectJars(File root, File dir, List<String> classPath, Set<String> seen) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectJars(root, file, classPath, seen);
            } else if (file.getName().endsWith(".jar")) {
                if (file.getParentFile().equals(root) && file.getName().toLowerCase().startsWith("minecraft")) {
                    continue;
                }
                add(classPath, seen, file);
            }
        }
    }

    private static void add(List<String> classPath, Set<String> seen, File file) {
        String path = file.getAbsolutePath();
        if (seen.add(path)) {
            classPath.add(path);
        }
    }
}

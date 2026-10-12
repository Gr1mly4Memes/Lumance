/*
 * Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package gr1mly4memes.lumance.compat;

import org.bukkit.Bukkit;

/**
 * Thread-safety checks for the single biggest source of plugin/mod friction on a hybrid server.
 *
 * <h2>The problem</h2>
 * Bukkit's contract is that almost the entire API is main-thread only. Plugins rely on that and do
 * no locking of their own. But Fabric mods are free to move work off the main thread, and the big
 * optimisation mods do exactly that: C2ME runs chunk generation and IO on worker threads, and its
 * {@code c2me-fixes-general-threading-issues} module patches Minecraft's own shutdown path to
 * <em>reject</em> an off-thread {@code save}. When CraftBukkit's shutdown thread then calls
 * {@code server.close()} off the main thread, that guard fires and the world is not saved - silent
 * data loss, with a stack trace in the log that is easy to miss.
 *
 * <p>So the asymmetry is: a mod can invalidate an assumption the plugin API still makes, and the
 * symptom appears somewhere else entirely.
 *
 * <h2>What this does</h2>
 * Nothing clever - it just makes the current thread's status cheap to query, and turns "this plugin
 * was called off-thread" into an immediate, named failure instead of a corrupted world an hour later.
 * Diagnostic use on the server side too: log whether async-capable mods are loaded, since that
 * changes which plugins are safe.
 *
 * <pre>{@code
 * ThreadGuard.assertMainThread("MyPlugin#onBlockBreak");
 * // ... or, to fail soft:
 * if (!ThreadGuard.isMainThread()) {
 *     Bukkit.getScheduler().runTask(plugin, this::work);
 *     return;
 * }
 * }</pre>
 *
 * <p>This touches no Bukkit or Spigot internals, so it is version-portable.
 */
public final class ThreadGuard {

    private ThreadGuard() {
    }

    /**
     * @return true when the calling thread is the server's primary thread
     */
    public static boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    /**
     * Asserts the caller is on the primary thread.
     *
     * @param what a short description of the operation, used in the failure message - e.g.
     *        {@code "MyPlugin#saveRegion"}
     * @throws IllegalStateException when called off-thread
     */
    public static void assertMainThread(String what) {
        if (!isMainThread()) {
            throw new IllegalStateException(
                    "Lumance: " + what + " must run on the primary thread, but was called from "
                            + Thread.currentThread().getName()
                            + ". Fabric mods move work off-thread; reschedule with"
                            + " Bukkit.getScheduler().runTask(...).");
        }
    }

    /**
     * Whether any loaded mod is known to move world or IO work off the primary thread.
     *
     * <p>Useful for diagnostics - a plugin whose correctness depends on synchronous world access
     * (world-edit style region saves, rollback, protection plugins) can warn an operator instead of
     * corrupting data silently.
     *
     * <p>Detection is by mod id and is deliberately conservative: a false negative only means no
     * warning is printed, so this never changes behaviour on its own.
     *
     * @return true when an async-chunk-system mod appears to be present
     */
    public static boolean asyncModsLikelyPresent() {
        return LoadedMods.isLoaded("c2me");
    }

    /**
     * A one-line summary of the threading situation, suitable for logging at startup.
     *
     * @return a human-readable description
     */
    public static String describe() {
        if (asyncModsLikelyPresent()) {
            return "async chunk system detected (C2ME"
                    + (LoadedMods.versionOf("c2me") == null ? "" : " " + LoadedMods.versionOf("c2me"))
                    + "): world and IO work may run off the primary thread,"
                    + " so main-thread-only plugin APIs can be called from the wrong thread";
        }
        return "no known async chunk mods loaded; primary-thread assumptions likely hold";
    }
}
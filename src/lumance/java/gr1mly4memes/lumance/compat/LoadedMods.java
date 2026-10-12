/*
 * Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package gr1mly4memes.lumance.compat;

/**
 * Mods that are present on this server, as a plain id -> version map.
 *
 * <p>Lumance deliberately reaches Fabric through <strong>reflection</strong> here rather than
 * compiling against {@code net.fabricmc.loader.api.FabricLoader}. Two reasons:
 *
 * <ul>
 *   <li>The Bukkit source set must not gain a hard Fabric compile dependency, otherwise every
 *       Minecraft upgrade has to reconcile Fabric's API surface as well as Mojang's.
 *   <li>Everything here degrades to "no mods known" instead of throwing
 *       {@link NoClassDefFoundError} if the loader is ever absent, which is what makes this
 *       portable to a non-Fabric Bukkit server unchanged.
 * </ul>
 *
 * <p>Typical plugin use - guard anything whose behaviour changes when a world-gen or optimisation
 * mod is installed:
 *
 * <pre>{@code
 * if (LoadedMods.isLoaded("lithium")) {
 *     // do not assume vanilla timings
 * }
 * }</pre>
 *
 * <p>Instances are safe to call from any thread; the snapshot is built once and cached.
 */
public final class LoadedMods {

    private LoadedMods() {
    }

    /** id -> version string, e.g. {@code c2me -> 0.4.2-alpha.0.88+26.3}. Never null. */
    private static volatile java.util.Map<String, String> snapshot;

    /**
     * @return an unmodifiable id -> version view of every mod loaded on this server, including
     *         Lumance and Fabric Loader itself. Empty if the loader API is unreachable.
     */
    public static java.util.Map<String, String> all() {
        java.util.Map<String, String> cached = snapshot;
        if (cached == null) {
            cached = query();
            snapshot = cached;
        }
        return cached;
    }

    /**
     * @param modId a Fabric mod id, e.g. {@code "c2me"}
     * @return true when that mod is loaded. Case-insensitive, and never throws.
     */
    public static boolean isLoaded(String modId) {
        return modId != null && all().containsKey(modId.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * @param modId a Fabric mod id
     * @return the mod's version string, or null when it is not loaded. Never throws.
     */
    public static String versionOf(String modId) {
        return modId == null ? null : all().get(modId.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Discards the cached snapshot. Fabric fixes up mod metadata during early startup, so a plugin
     * that runs very early can call this once to pick up late additions.
     */
    public static void refresh() {
        snapshot = null;
    }

    /**
     * Reads the mod list through Fabric's <em>public API interfaces</em>, never through the concrete
     * implementation classes.
     *
     * <p>This matters more than it looks. {@code container.getClass()} is a package-private
     * implementation class ({@code V1ModMetadata}), so {@code getClass().getMethod("getId")} resolves
     * fine but {@code invoke} throws {@link IllegalAccessException} - the caller cannot access a
     * member of a class that is not public. Looking the method up on the public
     * {@code net.fabricmc.loader.api.ModMetadata} interface instead makes the invocation legal, and
     * is stable across loader versions because that interface is what Fabric publishes.
     */
    private static java.util.Map<String, String> query() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Class<?> containerClass = Class.forName("net.fabricmc.loader.api.ModContainer");
            // ModMetadata lives in the api.metadata package, NOT directly under api.
            Class<?> metadataClass = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata");
            Class<?> versionClass = Class.forName("net.fabricmc.loader.api.Version");

            java.lang.reflect.Method getMetadata = containerClass.getMethod("getMetadata");
            java.lang.reflect.Method getId = metadataClass.getMethod("getId");
            java.lang.reflect.Method getVersion = metadataClass.getMethod("getVersion");
            java.lang.reflect.Method getFriendlyString = versionClass.getMethod("getFriendlyString");

            Object loader = loaderClass.getMethod("getInstance").invoke(null);
            Object mods = loaderClass.getMethod("getAllMods").invoke(loader);
            if (!(mods instanceof Iterable<?> iterable)) {
                return java.util.Collections.emptyMap();
            }
            for (Object container : iterable) {
                if (container == null) {
                    continue;
                }
                String modId = null;
                String version = "";
                try {
                    Object metadata = getMetadata.invoke(container);
                    Object id = getId.invoke(metadata);
                    modId = id instanceof String s ? s : null;
                    try {
                        Object versionObj = getVersion.invoke(metadata);
                        version = String.valueOf(getFriendlyString.invoke(versionObj));
                    } catch (ReflectiveOperationException | RuntimeException noVersion) {
                        // A mod with no usable version metadata still counts as loaded.
                    }
                } catch (ReflectiveOperationException | RuntimeException skipped) {
                    // One unreadable mod must not hide every other mod.
                    continue;
                }
                if (modId != null) {
                    out.put(modId.toLowerCase(java.util.Locale.ROOT), version);
                }
            }
        } catch (ClassNotFoundException | LinkageError absent) {
            // Not running under Fabric: report nothing rather than failing plugin code.
            return java.util.Collections.emptyMap();
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return out.isEmpty() ? java.util.Collections.emptyMap() : java.util.Collections.unmodifiableMap(out);
        }
        return java.util.Collections.unmodifiableMap(out);
    }
}
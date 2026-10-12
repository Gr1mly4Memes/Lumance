/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

/**
 * The patches/ tree holds server and brigadier patches side by side; the track
 * is derived from the patch path (without the .patch suffix).
 *
 * Lumance is a dedicated server: there is no client track any more. Client-only
 * paths are still claimed by SERVER rather than being ignored, so that a stray
 * patch under net/minecraft/client/ (or blaze3d/realmsclient/renderpearl) fails
 * loudly instead of silently doing nothing.
 */
public enum PatchTracks {
    SERVER,
    BRIGADIER;

    public static PatchTracks of(String name) {
        return PatchTracks.valueOf(name);
    }

    /** Whether a patch rel path (e.g. net/minecraft/server/Foo.java.patch) belongs to this track. */
    public boolean owns(String rel) {
        String target = rel.endsWith(".patch") ? rel.substring(0, rel.length() - ".patch".length()) : rel;
        if (this == BRIGADIER) {
            return target.startsWith("com/mojang/brigadier/");
        }
        return !target.startsWith("com/mojang/brigadier/");
    }
}
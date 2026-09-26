/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

/**
 * The patches/ tree holds server, client and brigadier patches side by side;
 * the track is derived from the patch path (without the .patch suffix).
 */
public enum PatchTracks {
    SERVER,
    CLIENT,
    BRIGADIER;

    public static PatchTracks of(String name) {
        return PatchTracks.valueOf(name);
    }

    /** Whether a patch rel path (e.g. net/minecraft/server/Foo.java.patch) belongs to this track. */
    public boolean owns(String rel) {
        String target = rel.endsWith(".patch") ? rel.substring(0, rel.length() - ".patch".length()) : rel;
        switch (this) {
            case BRIGADIER:
                return target.startsWith("com/mojang/brigadier/");
            case CLIENT:
                return target.startsWith("net/minecraft/client/")
                        || target.startsWith("com/mojang/realmsclient/")
                        || target.startsWith("com/mojang/blaze3d/")
                        || target.startsWith("com/mojang/renderpearl/");
            case SERVER:
            default:
                return !target.startsWith("com/mojang/brigadier/")
                        && !target.startsWith("net/minecraft/client/")
                        && !target.startsWith("com/mojang/realmsclient/")
                        && !target.startsWith("com/mojang/blaze3d/")
                        && !target.startsWith("com/mojang/renderpearl/");
        }
    }
}

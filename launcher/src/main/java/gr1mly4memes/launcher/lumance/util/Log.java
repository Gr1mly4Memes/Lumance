/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.launcher.lumance.util;

/** Tiny logging helper. */
public final class Log {
    private Log() {
    }

    public static void info(String message) {
        System.out.println("[Lumance] " + message);
    }

    public static void error(String message) {
        System.out.println("[Lumance] ERROR: " + message);
    }
}

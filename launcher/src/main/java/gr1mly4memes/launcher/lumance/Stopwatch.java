/*
 * Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only;
 */
package gr1mly4memes.launcher.lumance;

import gr1mly4memes.launcher.lumance.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Records how long each launcher startup step took and prints them in order.
 *
 * <h2>Why this exists</h2>
 * The steps before the game boots are almost all cache hits after the first run: the bundle is
 * verified rather than downloaded, and the overlay patch is skipped entirely when the patched jar
 * already hashes correctly. That makes startup fast - and makes it impossible to tell from the log
 * which step is slow when it is not. The log recorded <em>what</em> happened, not how long it took,
 * so a step that quietly started rewriting twenty thousand zip entries would look identical to one
 * that was skipped.
 *
 * <p>Each step therefore calls {@link #mark(String)}, and {@link #report()} prints the breakdown
 * once, just before the game process starts - directly above the game's own "Done (x.xxxs)!" line,
 * so the launcher and game costs can be compared.
 */
final class Stopwatch {

    private final List<String> names = new ArrayList<>();
    private final List<Long> millis = new ArrayList<>();
    private long last = System.nanoTime();

    /**
     * Records the time elapsed since the previous mark.
     *
     * @param step short name of the step that just finished
     */
    void mark(String step) {
        long now = System.nanoTime();
        names.add(step);
        millis.add((now - last) / 1_000_000L);
        last = now;
    }

    /** Prints every recorded step and the total. Safe to call when nothing was recorded. */
    void report() {
        if (names.isEmpty()) {
            return;
        }
        long total = 0;
        for (long ms : millis) {
            total += ms;
        }
        Log.info("Launcher steps (before the game process starts):");
        for (int i = 0; i < names.size(); i++) {
            Log.info(String.format("    %-18s %6d ms", names.get(i), millis.get(i)));
        }
        Log.info(String.format("    %-18s %6d ms", "TOTAL", total));
    }
}
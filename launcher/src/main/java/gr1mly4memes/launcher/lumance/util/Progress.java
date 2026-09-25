/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.launcher.lumance.util;

/**
 * Single-line progress bar for batch steps (library extraction, downloads).
 * On an interactive terminal the bar redraws in place via carriage return;
 * with piped/redirected output (or a dumb terminal) it stays quiet and only
 * the final summary line is printed, so log files get one line, not dozens.
 */
public final class Progress {
    private final String label;
    private final int total;
    private final boolean animated;
    private int done;
    private int lastLen;
    private boolean drawn;

    public Progress(String label, int total) {
        this(label, total, System.console() != null && !"dumb".equals(System.getenv("TERM")));
    }

    /**
     * Forces animation on or off. Useful for tests and for a future
     * {@code --no-progress} style flag; production code uses {@link #Progress(String, int)}.
     */
    public Progress(String label, int total, boolean animated) {
        this.label = label;
        this.total = total;
        this.animated = animated;
    }

    public void step() {
        update(done + 1);
    }

    public void update(int done) {
        this.done = done;
        if (!animated || total <= 0) {
            return;
        }
        draw(base());
    }

    /**
     * Re-renders the current line with extra in-flight detail (e.g. byte counts
     * for the file downloading now). No-op unless animating.
     */
    public void detail(String suffix) {
        if (!animated || total <= 0) {
            return;
        }
        draw(base() + " \u00b7 " + suffix);
    }

    public void finish(String summary) {
        String line = "[Lumance] " + summary;
        if (drawn) {
            System.out.print("\r" + pad(line) + "\n");
            System.out.flush();
        } else {
            Log.info(summary);
        }
    }

    private void draw(String line) {
        System.out.print("\r" + pad(line));
        System.out.flush();
        drawn = true;
    }

    private String base() {
        return "[Lumance] " + label + " " + bar(done, total, 30) + " " + Math.min(done, total) + "/" + total;
    }

    private String pad(String line) {
        lastLen = Math.max(lastLen, line.length());
        if (line.length() >= lastLen) {
            return line;
        }
        StringBuilder padded = new StringBuilder(line);
        while (padded.length() < lastLen) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /** Pure bar renderer: {@code [====>      ]}. */
    public static String bar(int done, int total, int width) {
        int filled = total <= 0 ? 0 : (int) ((long) Math.min(done, total) * width / total);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            if (i < filled) {
                bar.append('=');
            } else if (i == filled && filled < width) {
                bar.append('>');
            } else {
                bar.append(' ');
            }
        }
        return bar.append(']').toString();
    }
}

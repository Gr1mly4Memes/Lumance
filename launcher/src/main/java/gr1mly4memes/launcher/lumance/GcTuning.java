/*
 * Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only;
 */
package gr1mly4memes.launcher.lumance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the G1GC tuning the game process is launched with.
 *
 * <h2>Why this exists</h2>
 * The launcher previously passed no collector flags at all, so the server ran on whatever the JVM
 * picked: default G1 sizing, default young-gen sizing, and default tenuring. On a hybrid server
 * that means a small, busy old generation (lots of short-lived chunk and packet objects) and young
 * collections that copy survivors over and over. The symptom is uneven GC pauses, which show up as
 * tick-time spikes and, on a hybrid, as the occasional stall right when C2ME is generating chunks
 * on its workers.
 *
 * <h2>What is and is not touched</h2>
 * These are JVM flags. They are not visible on any classpath, so no Fabric mod and no Bukkit plugin
 * can observe or be broken by them - unlike an API or mixin change, which both can.
 *
 * <p>Only flags that are safe on any heap size are applied. Flags whose value has to match the heap
 * ({@code -Xms}, region size, GC ergonomics) are deliberately left to the operator, because
 * guessing them wrong costs more than it saves:
 * <ul>
 *   <li>{@code -Xms} is never set. Forcing the initial heap to the maximum would commit the whole
 *       heap up front and make "memory usage" worse, which is the opposite of the goal.</li>
 *   <li>{@code -XX:G1HeapRegionSize} is never set; the JVM derives it from the maximum heap.</li>
 * </ul>
 *
 * <h2>Opting out</h2>
 * Disable everything with {@code -Dlumance.gcTuning=false}, or let any of your own {@code -XX:}
 * flags win: this only skips itself if you already passed at least one.
 */
final class GcTuning {

    private GcTuning() {
    }

    /**
     * @param serverArgs the game arguments the operator passed after the jar name
     * @param jvmArgs    the arguments already assembled for the game process
     * @return flags to append, possibly empty
     */
    static List<String> jvmArgs(List<String> serverArgs, List<String> jvmArgs) {
        List<String> out = new ArrayList<>();
        if (hasUserCollectorFlag(serverArgs, jvmArgs)) {
            return out;
        }

        // Memory: a large fraction of a Minecraft server's live set is Strings (block/item/registry
        // names, translation keys, plugin messages). Letting G1 dedupe them typically cuts resident
        // size by a double-digit percentage for free.
        out.add("-XX:+UseStringDeduplication");

        // Stability: the G1 mixed-collection heuristic and a capped pause target keep a full GC from
        // turning into a multi-second stall while C2ME has the CPU busy. 50ms is the value the
        // Minecraft community settled on; it is a target, not a guarantee.
        out.add("-XX:MaxGCPauseMillis=50");
        out.add("-XX:+ParallelRefProcEnabled");

        // Deliberately NOT set, though they appear in most "optimised Minecraft" flag sets:
        //
        //   -XX:+AlwaysPreTouch   commits the entire max heap at startup. It smooths out later page
        //                           faults but makes resident memory larger from the first second,
        //                           which is the opposite of what we want here.
        //   -XX:G1NewSizePercent  still experimental in JDK 25: the JVM refuses to start without
        //   -XX:G1MaxNewSizePercent  -XX:+UnlockExperimentalVMOptions in front of it. Resizing eden
        //                           is a modest win and not worth either the extra flag or the risk
        //                           that the next JDK changes that requirement again.
        //   -XX:G1HeapRegionSize  the JVM already derives a sane value from the max heap; forcing
        //                           it is how flag sets from a 12G heap end up hurting a 3G one.
        //   -XX:MaxTenuringThreshold=1
        //                           copies survivors out of eden almost immediately. It trades
        //                           footprint for copy cost, and on a hybrid the worldgen threads are
        //                           already the scarce resource.

        // Shared memory perf files (/tmp/hsperfdata_*) are pure overhead on a server and are a common
        // source of permission noise in containers.
        out.add("-XX:-UsePerfData");

        return out;
    }

    /**
     * Forwards the heap size the operator gave the launcher on to the game process.
     *
     * <h2>Why this exists</h2>
     * The launcher does not run the game in-process. It builds a brand new {@code java} command
     * from scratch and starts it as a child, and JVM flags only bind in the JVM that receives
     * them. So {@code java -Xmx3G -jar lumance-server.jar} applied 3G to the launcher and to
     * nothing else: the child fell back to the default max heap, which is a quarter of physical
     * RAM. On a 32GB machine that is 8GB of heap the operator never asked for and never sees used,
     * and it is the single easiest way for the server to look like it is eating the machine.
     *
     * <p>So we read the heap the launcher actually got ({@link Runtime#maxMemory()}, which already
     * honours {@code -Xmx} and {@code -XX:MaxRAMPercentage}) and hand the same number to the child.
     * That reproduces whatever the operator intended instead of guessing it.
     *
     * <p>Nothing is emitted when the operator already stated a heap size among the game arguments,
     * so an explicit choice is never overridden.
     */
    static List<String> heapArgs(List<String> serverArgs) {
        List<String> out = new ArrayList<>();
        for (String arg : serverArgs) {
            if (arg.toLowerCase(Locale.ROOT).startsWith("-xmx")) {
                return out;
            }
        }
        long bytes = Runtime.getRuntime().maxMemory();
        if (bytes <= 0 || bytes == Long.MAX_VALUE) {
            return out;
        }
        // Round down to whole megabytes; a fractional value is not a legal -Xmx on every JVM.
        out.add("-Xmx" + (bytes / (1024L * 1024L)) + "M");
        return out;
    }

    /**
     * True when the operator already tuned the collector themselves. We check the game arguments too:
     * they are usually written before the jar name and reach the JVM the same way.
     */
    private static boolean hasUserCollectorFlag(List<String> serverArgs, List<String> jvmArgs) {
        for (String arg : concat(serverArgs, jvmArgs)) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("-xx:") || lower.startsWith("-xms") || lower.startsWith("-xmx")
                    || lower.startsWith("-xx:+useg1gc") || lower.startsWith("-xx:+usezgc")
                    || lower.startsWith("-xx:+usecms") || lower.startsWith("-xx:+useserialgc")
                    || lower.startsWith("-xx:+uselargepages")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }
}
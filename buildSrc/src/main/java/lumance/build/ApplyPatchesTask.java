/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import io.codechicken.diffpatch.cli.PatchOperation;
import io.codechicken.diffpatch.match.FuzzyLineMatcher;
import io.codechicken.diffpatch.util.Input;
import io.codechicken.diffpatch.util.LogLevel;
import io.codechicken.diffpatch.util.Output;
import io.codechicken.diffpatch.util.PatchMode;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Applies Lumance's own patches onto an existing sources tree with DiffPatch
 * (same engine, same fuzzy-offset tolerance as the NeoForm fixups).
 *
 * <p>An applied-marker records which patch files (by hash) are already reflected
 * in the tree, so repeat runs skip instead of failing: setup stays idempotent
 * while never touching uncommitted user edits. Whoever recreates the tree from
 * scratch (the sync tasks) deletes the marker first.
 *
 * <p>Markers go stale in one normal workflow: edit, {@code genPatches}, then
 * {@code setup} - the tree already contains the new patch, but the marker
 * predates it. So a patch that fails to apply is not rejected outright: it is
 * applied to the matching clean file and compared byte-for-byte with the
 * current target. Identical bytes mean "already in the tree" (skipped and
 * recorded); anything else is genuinely broken and still fails loudly.
 *
 * <p>Hand-dropped patches that no longer match exactly are NOT fuzzed here:
 * that lives behind the opt-in {@code fuzzy} flag ({@code fuzzyApplyPatches}),
 * which gets one extra fuzzy pass per file and reports hits loudly. Truly
 * conflicting patches fail the build either way.
 */
public abstract class ApplyPatchesTask extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getPatchedSources();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getPatchesDir();

    /**
     * Pre-patch sources for this track (what genPatches diffs against). Only
     * used to recognise already-applied patches; never written to.
     */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getCleanSources();

    @OutputFile
    public abstract RegularFileProperty getMarkerFile();

    @org.gradle.api.tasks.Input
    public abstract org.gradle.api.provider.Property<String> getTrack();

    /**
     * Opt-in fuzzy fallback for hand-dropped patches (the fuzzyApplyPatches
     * tasks). Off by default: applyPatches stays strict.
     */
    @org.gradle.api.tasks.Input
    public abstract org.gradle.api.provider.Property<Boolean> getFuzzy();

    /**
     * Min fuzz for the fuzzy lane (PaperMC-style): the minimum match quality
     * for a hunk to land. Default 0.5, override per-run, e.g.
     * {@code gradlew fuzzyApplyPatches --min-fuzz=0.3}.
     */
    @org.gradle.api.tasks.Input
    @org.gradle.api.tasks.Optional
    @org.gradle.api.tasks.options.Option(option = "min-fuzz",
            description = "Min fuzz. The minimum quality needed for a hunk to be applied. Default is 0.5.")
    public abstract org.gradle.api.provider.Property<String> getMinFuzz();

    /**
     * Where rejected hunks go (mirroring target layout, e.g.
     * {@code rejects/net/minecraft/Foo.java.rej}). Only used by the fuzzy
     * lane; a plain path string so a not-yet-existing dir stays valid.
     */
    @org.gradle.api.tasks.Input
    @org.gradle.api.tasks.Optional
    public abstract org.gradle.api.provider.Property<String> getRejectsDir();

    @TaskAction
    public void run() throws Exception {
        PatchTracks track = PatchTracks.of(getTrack().get());
        Path root = getPatchesDir().getAsFile().get().toPath();
        Path targetRoot = getPatchedSources().getAsFile().get().toPath();
        File markerFile = getMarkerFile().getAsFile().get();

        List<Path> patchFiles = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(".patch")) {
                        String rel = root.relativize(file).toString().replace(File.separatorChar, '/');
                        if (track.owns(rel)) {
                            patchFiles.add(file);
                        }
                    }
                }
            }
        }
        patchFiles.sort(java.util.Comparator.comparing(Path::toString));
        if (patchFiles.isEmpty()) {
            getLogger().lifecycle("[Lumance] No patches to apply");
            return;
        }

        Map<String, String> applied = readMarker(markerFile);
        int done = 0;
        int fuzzy = 0;
        int partial = 0;
        int skipped = 0;
        Map<String, String> updated = new HashMap<>(applied);
        Path cleanRoot = getCleanSources().getAsFile().get().toPath();
        for (Path patch : patchFiles) {
            String rel = root.relativize(patch).toString().replace(File.separatorChar, '/');
            String hash = Hashes.toHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(patch)));
            if (hash.equals(applied.get(rel))) {
                skipped++;
                continue;
            }
            String targetRel = rel.substring(0, rel.length() - ".patch".length());
            Path target = resolveTarget(targetRoot, targetRel, rel);
            Path cleanTarget = resolveTarget(cleanRoot, targetRel, rel);
            Path rejFile = rejectsFile(targetRel, target);
            float fuzzyMin = Float.parseFloat(getMinFuzz().getOrElse("0.5"));
            int result = applySingle(patch, target, cleanTarget, rejFile, getFuzzy().get(), fuzzyMin, getLogger());
            if (result == 1) {
                done++;
            } else if (result == 2) {
                done++;
                fuzzy++;
                getLogger().warn("[Lumance] {} applied WITH FUZZ - run genPatches to rebase it", rel);
            } else if (result == 3) {
                done++;
                partial++;
            } else {
                skipped++;
            }
            updated.put(rel, hash);
        }
        markerFile.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(markerFile), StandardCharsets.UTF_8)) {
            List<String> keys = new ArrayList<>(updated.keySet());
            java.util.Collections.sort(keys);
            for (String key : keys) {
                writer.write(key + "\t" + updated.get(key) + "\n");
            }
        }
        if (fuzzy > 0 || partial > 0) {
            getLogger().lifecycle("[Lumance] Patches: {} applied ({} with fuzz, {} partial with rejects - rebase via genPatches), {} already applied",
                    done, fuzzy, partial, skipped);
        } else {
            getLogger().lifecycle("[Lumance] Patches: {} applied, {} already applied", done, skipped);
        }
    }

    /**
     * Resolves a patch's target file. Patch files are named {@code <path>.java.patch},
     * but hand-dropped files often lose the {@code .java} (e.g. CraftBukkit's
     * {@code AdvancementHolder.patch}): when the exact target is missing but
     * {@code target + ".java"} exists, that wins. Otherwise the exact path is
     * returned so the error message shows what was expected.
     */
    private static Path resolveTarget(Path root, String targetRel, String patchRel) {
        Path exact = root.resolve(targetRel.replace('/', File.separatorChar));
        if (Files.exists(exact) || targetRel.endsWith(".java")) {
            return exact;
        }
        Path withJava = root.resolve((targetRel + ".java").replace('/', File.separatorChar));
        return Files.exists(withJava) ? withJava : exact;
    }

    /**
     * Where this target's rejects live: the shared rejects tree when
     * configured, mirroring the target layout - otherwise next to the target.
     */
    private Path rejectsFile(String targetRel, Path target) {
        if (getRejectsDir().isPresent()) {
            return new File(getRejectsDir().get(),
                    (targetRel + ".rej").replace('/', File.separatorChar)).toPath();
        }
        return target.resolveSibling(target.getFileName() + ".rej");
    }

    /**
     * Applies one patch file onto its target file (created if the patch adds it).
     *
     * @return 1 when applied exactly, 2 when applied with fuzz (only attempted
     *         when {@code fuzzy} is set), 3 for a partial fuzzy application with
     *         the rejected hunks parked in the rejects tree, 0 when the target
     *         already contained it byte-for-byte (verified against clean).
     * @throws IllegalStateException when the patch applies nowhere, reporting
     *         failed/total hunks like PaperMC does.
     */
    static int applySingle(Path patch, Path target, Path cleanTarget, Path rejFile, boolean fuzzy, float fuzzyMin,
            org.gradle.api.logging.Logger log) throws Exception {
        byte[] patchBytes = Files.readAllBytes(patch);
        byte[] current = Files.exists(target) ? Files.readAllBytes(target) : new byte[0];
        byte[] cleanBase = Files.exists(cleanTarget) ? Files.readAllBytes(cleanTarget) : new byte[0];
        // A "successful" run whose output equals its input placed nothing (e.g.
        // an unparseable patch yielding zero hunks): treat it as a failure, or
        // empty/missing targets would vacuous-skip everything.
        PatchAttempt strict = tryPatch(patch, patchBytes, current, target.toString(),
                PatchMode.OFFSET, FuzzyLineMatcher.DEFAULT_MIN_MATCH_SCORE, null);
        if (strict.bytes() != null && !java.util.Arrays.equals(strict.bytes(), current)) {
            Files.createDirectories(target.getParent());
            Files.write(target, strict.bytes());
            return 1;
        }
        PatchAttempt last = strict;
        if (fuzzy) {
            Path rejTmp = Files.createTempFile("lumance-rejects", ".rej");
            try {
                Files.deleteIfExists(rejTmp);
                PatchAttempt fuzzyAttempt = tryPatch(patch, patchBytes, current, target.toString(),
                        PatchMode.FUZZY, fuzzyMin, rejTmp);
                last = fuzzyAttempt;
                int rejectedHunks = countRejHunks(rejTmp);
                if (fuzzyAttempt.bytes() != null && rejectedHunks == 0
                        && !java.util.Arrays.equals(fuzzyAttempt.bytes(), current)) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, fuzzyAttempt.bytes());
                    return 2;
                }
                if (fuzzyAttempt.bytes() != null && !java.util.Arrays.equals(fuzzyAttempt.bytes(), cleanBase)) {
                    // Paper-style partial: placeable hunks land, the rest park
                    // in the rejects tree for hand-porting.
                    if (java.util.Arrays.equals(fuzzyAttempt.bytes(), current)) {
                        return 0;
                    }
                    Files.createDirectories(target.getParent());
                    Files.write(target, fuzzyAttempt.bytes());
                    Files.createDirectories(rejFile.getParent());
                    Files.writeString(rejFile, Files.readString(rejTmp, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                    log.warn("[Lumance] {} partially applied ({} hunks rejected - see {})",
                            patch.getFileName(), rejectedHunks, displayPath(rejFile));
                    return 3;
                }
            } finally {
                Files.deleteIfExists(rejTmp);
            }
        }
        PatchAttempt expected = tryPatch(patch, patchBytes, cleanBase, cleanTarget.toString(),
                PatchMode.OFFSET, FuzzyLineMatcher.DEFAULT_MIN_MATCH_SCORE, null);
        if (expected.bytes() != null && java.util.Arrays.equals(expected.bytes(), current)
                && !java.util.Arrays.equals(expected.bytes(), cleanBase)) {
            Files.deleteIfExists(rejFile);
            return 0;
        }
        String hint;
        if (Files.exists(target)) {
            hint = "";
        } else if (!patch.getFileName().toString().endsWith(".java.patch")) {
            hint = " (missing target - patch files must be named <path>.java.patch, e.g. AdvancementHolder.java.patch)";
        } else {
            hint = " (missing target - no such file in this track's tree)";
        }
        throw new IllegalStateException("Patch does not apply: " + patch + " (" + last.failedHunks()
                + "/" + last.totalHunks() + " hunks failed, target: " + target + ")" + hint);
    }

    /** One DiffPatch run: patched bytes (full, partial, or null) plus hunk counts. */
    record PatchAttempt(byte[] bytes, int failedHunks, int totalHunks) {
    }

    /** Project-relative display form (falls back to absolute across drives). */
    private static String displayPath(Path file) {
        try {
            return Path.of("").toAbsolutePath().relativize(file.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    /** Counts hunk headers in a rejects file (0 when there is none). */
    private static int countRejHunks(Path rejTmp) throws Exception {
        if (!Files.exists(rejTmp)) {
            return 0;
        }
        int hunks = 0;
        for (String line : Files.readAllLines(rejTmp, StandardCharsets.UTF_8)) {
            if (line.startsWith("@@")) {
                hunks++;
            }
        }
        return hunks;
    }

    /**
     * Runs DiffPatch in memory, collecting rejects when {@code rejTmp} is given.
     * Returns the patched bytes - full on exit 0, partial when hunks were
     * rejected - or null bytes when nothing could be placed. Hunk counts come
     * along either way for PaperMC-style failure messages.
     */
    private static PatchAttempt tryPatch(Path patch, byte[] patchBytes, byte[] base, String baseName,
            PatchMode mode, float minFuzz, Path rejTmp) throws Exception {
        Path tmp = Files.createTempFile("lumance-patch", ".java");
        try {
            var builder = PatchOperation.builder()
                    .logTo(line -> {
                    })
                    .baseInput(Input.SingleInput.pipe(new ByteArrayInputStream(base), baseName))
                    .patchesInput(Input.SingleInput.pipe(new ByteArrayInputStream(patchBytes), patch.toString()))
                    .patchedOutput(Output.SingleOutput.path(tmp))
                    .level(LogLevel.WARN)
                    .mode(mode)
                    .minFuzz(minFuzz);
            if (rejTmp != null) {
                builder.summary(true).rejectsOutput(Output.SingleOutput.path(rejTmp)).rejectsAsPatches(true);
            }
            var result = builder.lineEnding("\n").build().operate();
            var summary = result.summary;
            int failed = summary != null ? summary.failedMatches : 0;
            int total = summary != null
                    ? summary.failedMatches + summary.exactMatches + summary.accessMatches + summary.offsetMatches + summary.fuzzyMatches
                    : 0;
            if (result.exit != 0 && (rejTmp == null || countRejHunks(rejTmp) == 0)) {
                return new PatchAttempt(null, failed, total);
            }
            if (!Files.exists(tmp)) {
                return new PatchAttempt(null, failed, total);
            }
            return new PatchAttempt(Files.readAllBytes(tmp), failed, total);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Map<String, String> readMarker(File markerFile) throws Exception {
        Map<String, String> applied = new HashMap<>();
        if (!markerFile.exists()) {
            return applied;
        }
        for (String line : Files.readAllLines(markerFile.toPath(), StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t");
            if (parts.length == 2) {
                applied.put(parts[0], parts[1]);
            }
        }
        return applied;
    }
}

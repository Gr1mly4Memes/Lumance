/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import io.codechicken.diffpatch.cli.PatchOperation;
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
            Path target = targetRoot.resolve(targetRel.replace('/', File.separatorChar));
            Path cleanTarget = cleanRoot.resolve(targetRel.replace('/', File.separatorChar));
            int result = applySingle(patch, target, cleanTarget, getFuzzy().get(), getLogger());
            if (result == 1) {
                done++;
            } else if (result == 2) {
                done++;
                fuzzy++;
                getLogger().warn("[Lumance] {} applied WITH FUZZ - run genPatches to rebase it", rel);
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
        if (fuzzy > 0) {
            getLogger().lifecycle("[Lumance] Patches: {} applied ({} with fuzz - rebase via genPatches), {} already applied",
                    done, fuzzy, skipped);
        } else {
            getLogger().lifecycle("[Lumance] Patches: {} applied, {} already applied", done, skipped);
        }
    }

    /**
     * Applies one patch file onto its target file (created if the patch adds it).
     *
     * @return 1 when applied exactly, 2 when applied with fuzz (only attempted
     *         when {@code fuzzy} is set), 0 when the target already contained it
     *         byte-for-byte (verified against clean).
     * @throws IllegalStateException when the patch applies nowhere.
     */
    static int applySingle(Path patch, Path target, Path cleanTarget, boolean fuzzy,
            org.gradle.api.logging.Logger log) throws Exception {
        byte[] patchBytes = Files.readAllBytes(patch);
        byte[] current = Files.exists(target) ? Files.readAllBytes(target) : new byte[0];
        byte[] applied = tryPatch(patch, patchBytes, current, target.toString(), PatchMode.OFFSET);
        if (applied != null) {
            Files.createDirectories(target.getParent());
            Files.write(target, applied);
            return 1;
        }
        if (fuzzy) {
            byte[] fuzzyApplied = tryPatch(patch, patchBytes, current, target.toString(), PatchMode.FUZZY);
            if (fuzzyApplied != null) {
                Files.createDirectories(target.getParent());
                Files.write(target, fuzzyApplied);
                return 2;
            }
        }
        byte[] cleanBase = Files.exists(cleanTarget) ? Files.readAllBytes(cleanTarget) : new byte[0];
        byte[] expected = tryPatch(patch, patchBytes, cleanBase, cleanTarget.toString(), PatchMode.OFFSET);
        if (expected != null && java.util.Arrays.equals(expected, current)) {
            return 0;
        }
        throw new IllegalStateException("Patch does not apply: " + patch + " (target: " + target + ")");
    }

    /** Runs DiffPatch in memory; returns the patched bytes, or null when it fails. */
    private static byte[] tryPatch(Path patch, byte[] patchBytes, byte[] base, String baseName, PatchMode mode) throws Exception {
        Path tmp = Files.createTempFile("lumance-patch", ".java");
        try {
            var result = PatchOperation.builder()
                    .logTo(line -> {
                    })
                    .baseInput(Input.SingleInput.pipe(new ByteArrayInputStream(base), baseName))
                    .patchesInput(Input.SingleInput.pipe(new ByteArrayInputStream(patchBytes), patch.toString()))
                    .patchedOutput(Output.SingleOutput.path(tmp))
                    .level(LogLevel.WARN)
                    .mode(mode)
                    .lineEnding("\n")
                    .build()
                    .operate();
            if (result.exit != 0) {
                return null;
            }
            return Files.readAllBytes(tmp);
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

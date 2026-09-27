/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import io.codechicken.diffpatch.cli.DiffOperation;
import io.codechicken.diffpatch.util.Input;
import io.codechicken.diffpatch.util.LogLevel;
import io.codechicken.diffpatch.util.Output;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Regenerates this track's patches/*.patch from the edited tree by diffing it
 * against the clean sources. Files that no longer differ lose their patch
 * (delete-to-revert); the state file remembers which patches this task
 * manages so it never touches foreign files. Deleting a patch by hand counts
 * as a revert only once the tree matches clean again - until then the next
 * setup re-applies whatever is still on disk.
 */
public abstract class GenPatchesTask extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getCleanSources();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getPatchedSources();

    @org.gradle.api.tasks.OutputDirectory
    public abstract DirectoryProperty getPatchesDir();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getWorkDirectory();

    @org.gradle.api.tasks.Input
    public abstract Property<String> getStateName();

    @org.gradle.api.tasks.Input
    public abstract Property<String> getTrack();

    @TaskAction
    public void run() throws Exception {
        PatchTracks track = PatchTracks.of(getTrack().get());
        Path cleanRoot = getCleanSources().getAsFile().get().toPath();
        Path patchedRoot = getPatchedSources().getAsFile().get().toPath();
        File patchesRoot = getPatchesDir().getAsFile().get();
        File stateFile = new File(getWorkDirectory().getAsFile().get(), getStateName().get());

        Map<String, String> previous = readState(stateFile);
        Map<String, String> current = new HashMap<>();
        int written = 0;
        int removed = 0;

        List<Path> patchedFiles = new ArrayList<>();
        if (Files.isDirectory(patchedRoot)) {
            try (var stream = Files.walk(patchedRoot)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(".java")) {
                        patchedFiles.add(file);
                    }
                }
            }
        }
        for (Path patchedFile : patchedFiles) {
            String rel = patchedRoot.relativize(patchedFile).toString().replace(File.separatorChar, '/');
            if (!track.owns(rel + ".patch")) {
                continue;
            }
            Path cleanFile = cleanRoot.resolve(rel.replace('/', File.separatorChar));
            byte[] cleanBytes = Files.exists(cleanFile) ? Files.readAllBytes(cleanFile) : new byte[0];
            byte[] patchedBytes = Files.readAllBytes(patchedFile);
            if (java.util.Arrays.equals(cleanBytes, patchedBytes)) {
                continue;
            }
            File patchFile = new File(patchesRoot, (rel + ".patch").replace('/', File.separatorChar));
            if (!patchFile.isFile() && previous.containsKey(rel + ".patch")) {
                // The patch was deleted by hand: that means revert. Restore the
                // tree file from clean instead of resurrecting the patch.
                if (Files.exists(cleanFile)) {
                    Files.copy(cleanFile, patchedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.delete(patchedFile);
                }
                getLogger().lifecycle("[Lumance] Reverted {} (patch deleted)", rel);
                continue;
            }
            String patch = diff(rel, cleanBytes, patchedBytes);
            if (patch.isEmpty()) {
                continue;
            }
            patchFile.getParentFile().mkdirs();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(patchFile), StandardCharsets.UTF_8)) {
                writer.write(patch);
            }
            current.put(rel + ".patch", Hashes.sha256(patchFile));
            written++;
            getLogger().lifecycle("[Lumance] Updated {}", rel + ".patch");
        }

        for (Map.Entry<String, String> entry : previous.entrySet()) {
            String rel = entry.getKey();
            if (current.containsKey(rel) || !track.owns(rel)) {
                continue;
            }
            File patchFile = new File(patchesRoot, rel.replace('/', File.separatorChar));
            if (patchFile.isFile()) {
                try {
                    if (Hashes.sha256(patchFile).equals(entry.getValue())) {
                        if (patchFile.delete()) {
                            removed++;
                            getLogger().lifecycle("[Lumance] Removed {} (reverted)", rel);
                        }
                    }
                } catch (Exception e) {
                    getLogger().warn("[Lumance] Could not remove stale patch {}: {}", rel, e.getMessage());
                }
            }
        }

        stateFile.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(stateFile), StandardCharsets.UTF_8)) {
            List<String> keys = new ArrayList<>(current.keySet());
            java.util.Collections.sort(keys);
            for (String key : keys) {
                writer.write(key + "\t" + current.get(key) + "\n");
            }
        }
        if (written == 0 && removed == 0) {
            getLogger().lifecycle("[Lumance] No patch changes");
        }
    }

    private static String diff(String rel, byte[] cleanBytes, byte[] patchedBytes) throws Exception {
        Path tmp = Files.createTempFile("lumance-genpatch", ".patch");
        try {
            var result = DiffOperation.builder()
                    .logTo(line -> {
                    })
                    .baseInput(Input.SingleInput.pipe(new ByteArrayInputStream(cleanBytes), "a/" + rel))
                    .changedInput(Input.SingleInput.pipe(new ByteArrayInputStream(patchedBytes), "b/" + rel))
                    .aPrefix("a/")
                    .bPrefix("b/")
                    .autoHeader(true)
                    .context(3)
                    .patchesOutput(Output.SingleOutput.path(tmp))
                    .level(LogLevel.WARN)
                    .lineEnding("\n")
                    .build()
                    .operate();
            // DiffOperation uses diff-like exits: 0 = no changes, 1 = diff
            // produced, -1 = error. A produced diff is success, not failure.
            if (result.exit == 0) {
                return "";
            }
            if (result.exit != 1) {
                throw new IllegalStateException("Could not diff " + rel + " (exit " + result.exit + ")");
            }
            return Files.readString(tmp, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Map<String, String> readState(File stateFile) throws Exception {
        Map<String, String> state = new HashMap<>();
        if (!stateFile.exists()) {
            return state;
        }
        for (String line : Files.readAllLines(stateFile.toPath(), StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t");
            if (parts.length == 2) {
                state.put(parts[0], parts[1]);
            }
        }
        return state;
    }
}

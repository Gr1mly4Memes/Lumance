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
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Builds a clean sources tree: raw decompiler output plus the NeoForm fixup
 * patches (same engine, same fuzzy-offset tolerance as the Lumance patches).
 * Fixups whose target file does not exist in this tree are skipped: the shared
 * NeoForm patch dir covers both game sides. When shared sources are given,
 * files byte-identical to them are dropped (the server tree owns shared code).
 */
public abstract class PrepareCleanSourcesTask extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getRawSources();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getPatchesDir();

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getExtraPatchesDirs();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    @org.gradle.api.tasks.Optional
    public abstract DirectoryProperty getSharedSources();

    @OutputDirectory
    public abstract DirectoryProperty getCleanSources();

    @OutputFile
    public abstract RegularFileProperty getMarker();

    @TaskAction
    public void run() throws Exception {
        File rawDir = getRawSources().getAsFile().get();
        File cleanDir = getCleanSources().getAsFile().get();
        deleteRecursively(cleanDir);
        copyJavaTree(rawDir.toPath(), cleanDir.toPath());

        List<File> patchDirs = new ArrayList<>();
        patchDirs.add(getPatchesDir().getAsFile().get());
        for (File extra : getExtraPatchesDirs().getFiles()) {
            patchDirs.add(extra);
        }
        int applied = 0;
        int fuzzy = 0;
        int skipped = 0;
        for (File patchDir : patchDirs) {
            if (!patchDir.isDirectory()) {
                continue;
            }
            List<Path> patches = new ArrayList<>();
            try (var stream = Files.walk(patchDir.toPath())) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(".patch")) {
                        patches.add(file);
                    }
                }
            }
            patches.sort(java.util.Comparator.comparing(Path::toString));
            for (Path patch : patches) {
                String rel = patchDir.toPath().relativize(patch).toString().replace(File.separatorChar, '/');
                String targetRel = rel.substring(0, rel.length() - ".patch".length());
                File target = new File(cleanDir, targetRel.replace('/', File.separatorChar));
                if (!target.isFile()) {
                    skipped++;
                    continue;
                }
                if (applySingle(patch.toFile(), target)) {
                    applied++;
                } else {
                    fuzzy++;
                    getLogger().warn("[Lumance] NeoForm fixup applied with fuzz: {}", rel);
                }
            }
        }
        if (fuzzy > 0) {
            getLogger().lifecycle("[Lumance] Clean sources: {} fixups applied ({} with fuzz), {} skipped (other side)",
                    applied + fuzzy, fuzzy, skipped);
        } else {
            getLogger().lifecycle("[Lumance] Clean sources: {} fixups applied, {} skipped (other side)", applied, skipped);
        }

        if (getSharedSources().isPresent()) {
            File sharedDir = getSharedSources().getAsFile().get();
            int dropped = 0;
            List<Path> files = new ArrayList<>();
            try (var stream = Files.walk(cleanDir.toPath())) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(".java")) {
                        files.add(file);
                    }
                }
            }
            for (Path file : files) {
                Path rel = cleanDir.toPath().relativize(file);
                File counterpart = sharedDir.toPath().resolve(rel).toFile();
                if (counterpart.isFile() && Files.mismatch(file, counterpart.toPath()) == -1) {
                    Files.delete(file);
                    dropped++;
                }
            }
            pruneEmptyDirs(cleanDir);
            getLogger().lifecycle("[Lumance] Clean sources: {} files shared with the server tree dropped", dropped);
        }

        File marker = getMarker().getAsFile().get();
        marker.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(marker), StandardCharsets.UTF_8)) {
            writer.write("ok\n");
        }
    }

    /**
     * @return true for an exact/offset hit, false when the fuzz fallback placed it.
     */
    private static boolean applySingle(File patch, File target) throws Exception {
        byte[] base = Files.readAllBytes(target.toPath());
        byte[] patchBytes = Files.readAllBytes(patch.toPath());
        byte[] applied = tryPatch(patch, patchBytes, base, target.toString(), PatchMode.OFFSET);
        if (applied == null) {
            applied = tryPatch(patch, patchBytes, base, target.toString(), PatchMode.FUZZY);
            if (applied == null) {
                throw new IllegalStateException("NeoForm fixup does not apply: " + patch + " (target: " + target + ")");
            }
            Files.write(target.toPath(), applied);
            return false;
        }
        Files.write(target.toPath(), applied);
        return true;
    }

    private static byte[] tryPatch(File patch, byte[] patchBytes, byte[] base, String baseName, PatchMode mode) throws Exception {
        Path tmp = Files.createTempFile("lumance-fixup", ".java");
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

    private static void copyJavaTree(Path from, Path to) throws Exception {
        try (var stream = Files.walk(from)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(file) && file.toString().endsWith(".java")) {
                    Path target = to.resolve(from.relativize(file));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            }
        }
    }

    private static void pruneEmptyDirs(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                pruneEmptyDirs(child);
            }
        }
        children = dir.listFiles();
        if (children != null && children.length == 0) {
            dir.delete();
        }
    }

    private static void deleteRecursively(File file) throws Exception {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            throw new IllegalStateException("Could not delete " + file);
        }
    }
}

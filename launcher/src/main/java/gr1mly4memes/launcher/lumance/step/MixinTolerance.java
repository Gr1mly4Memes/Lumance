/*
 * Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package gr1mly4memes.launcher.lumance.step;

import gr1mly4memes.launcher.lumance.util.Log;
import gr1mly4memes.launcher.lumance.util.Sha1;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Softens mixin failure handling across every mod, at load time, without editing mod jars.
 *
 * <h2>Why this exists</h2>
 * A Fabric mod anchors its mixins on exact descriptors and lexical positions inside vanilla methods.
 * Any CraftBukkit change to a signature, a local variable table, or a call site breaks that anchor.
 * Today those four situations produced four separate hard crashes, each of which took a boot cycle
 * to find:
 *
 * <ul>
 *   <li>{@code FireBlock.checkBurnOut} - CraftBukkit appends a parameter, so the mod's
 *       {@code @Inject} descriptor no longer matches.</li>
 *   <li>{@code Mob.aiStep} - an inlined local vanishes from the LVT.</li>
 *   <li>{@code ExperienceOrb.followNearbyPlayer} - a second {@code Player} local makes
 *       {@code @ModifyVariable} ambiguous.</li>
 *   <li>{@code ReplaceBlock.apply} - a call CraftBukkit folded into a helper has no target left.</li>
 * </ul>
 *
 * None of these are repairable from the server side: the required-threshold lives in the mod's own
 * bytecode. What <em>is</em> controllable is whether one broken anchor takes the whole process down.
 * Mixin's {@code InjectionInfo.postInject} compares the injected-callback count against
 * {@code requiredCallbackCount}, which is seeded from the mod's mixin config. Lower that to 0 and a
 * failed anchor becomes a skipped injection plus a warning instead of an {@link Error}.
 *
 * <h2>What it does</h2>
 * For every mod in {@code mods/}, copy it into a shadow folder and rewrite its mixin configs to
 * {@code "required": false} plus {@code "injectors": {"defaultRequire": 0}}, then point Fabric at
 * the shadow folder via {@code -Dfabric.modsFolder}. The user's jars are never modified, and deleting
 * the shadow folder is always safe.
 *
 * <h2>What it deliberately does not do</h2>
 * It does not repair the anchor. A degraded mod is a mod missing one injection, which can be worse
 * than a crash - a protection mod that silently stops applying is more dangerous than one that
 * refuses to start. That is why every rewrite is logged, and why this is a policy the operator can
 * turn off rather than something buried in the loader.
 */
public final class MixinTolerance {
    private MixinTolerance() {
    }

    /** Result of one pass, for the startup summary. */
    public record Result(String folder, int mods, int configsRewritten, int modsCopied) {
    }

    /**
     * Builds the shadow mods folder and returns it, or null when the policy is disabled or there is
     * nothing to do.
     *
     * @param modsDir the user's {@code mods} folder
     * @param shadowRoot cache directory the shadow folder is created under
     * @param enabled operator opt-out
     */
    public static Result apply(File modsDir, File shadowRoot, boolean enabled) throws IOException {
        if (!enabled) {
            Log.info("Mixin tolerance disabled (mixin-tolerance=false)");
            return null;
        }
        File[] jars = modsDir.isDirectory() ? modsDir.listFiles() : null;
        if (jars == null) {
            return null;
        }
        java.util.Arrays.sort(jars);
        List<File> modJars = new ArrayList<>();
        for (File file : jars) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                modJars.add(file);
            }
        }
        if (modJars.isEmpty()) {
            return null;
        }

        // One folder per source-mod-set fingerprint, so an unchanged mods/ reuses the previous
        // shadow folder wholesale and a changed one is rebuilt from scratch instead of leaving
        // stale patched jars behind.
        StringBuilder fingerprint = new StringBuilder();
        for (File jar : modJars) {
            fingerprint.append(jar.getName()).append(':').append(Sha1.sha256(jar)).append(';');
        }
        String id = Sha1.shortId(Sha1.sha256(fingerprint.toString().getBytes(StandardCharsets.UTF_8)));
        File shadow = new File(shadowRoot, "mods-tolerant-" + id);

        // Lumance - actually reuse the previous shadow folder.
        File marker = new File(shadow, COMPLETE_MARKER);
        if (marker.isFile()) {
            Integer configs = readRewrittenCount(marker);
            int present = 0;
            for (File jar : modJars) {
                if (new File(shadow, jar.getName()).isFile()) {
                    present++;
                }
            }
            if (configs != null && present == modJars.size()) {
                return new Result(shadow.getAbsolutePath(), modJars.size(), configs, present);
            }
            // Marker present but the folder does not match: fall through and rebuild it.
        }

        if (shadow.isDirectory()) {
            deleteRecursively(shadow);
        }
        if (!shadow.mkdirs()) {
            throw new IOException("Could not create shadow mods folder " + shadow);
        }

        int configs = 0;
        int copied = 0;
        for (File jar : modJars) {
            try {
                int rewritten = copyWithTolerance(jar, new File(shadow, jar.getName()));
                configs += rewritten;
                copied++;
            } catch (IOException failed) {
                // A mod we cannot read is not a reason to refuse to start. Fall back to using the
                // original jar so the mod still loads, minus the tolerance it would have had.
                Log.error("Could not prepare " + jar.getName() + " for mixin tolerance ("
                        + failed.getMessage() + ") - loading it unmodified");
                Files.copy(jar.toPath(), new File(shadow, jar.getName()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        // Lumance - stamp the finished folder so the next boot can reuse it. This must be the last
        // thing written; anything after it would not be covered by the marker.
        Files.writeString(marker.toPath(), Integer.toString(configs), StandardCharsets.UTF_8);

        return new Result(shadow.getAbsolutePath(), modJars.size(), configs, copied);
    }

    /** Written into a finished shadow folder to mark it safe to reuse; see {@link #apply}. */
    private static final String COMPLETE_MARKER = ".lumance-shadow-complete";

    /**
     * Reads the rewritten-config count back out of a completion marker.
     *
     * @return the count, or {@code null} if the marker is missing or unreadable, which makes the
     *         caller rebuild the folder rather than guess
     */
    private static Integer readRewrittenCount(File marker) {
        try {
            return Integer.valueOf(Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException unreadable) {
            return null;
        }
    }

    /**
     * Copies one mod jar, rewriting any mixin config it contains. Unchanged jars are copied
     * byte-for-byte so we do not needlessly churn files the operator may be watching.
     *
     * @return how many mixin configs were rewritten
     */
    private static int copyWithTolerance(File source, File target) throws IOException {
        Map<String, byte[]> rewritten = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(source)) {
            for (Enumeration<JarEntry> it = jar.entries(); it.hasMoreElements(); ) {
                JarEntry entry = it.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.endsWith(".json") || !name.contains("mixin")) {
                    continue;
                }
                byte[] original;
                try (InputStream in = jar.getInputStream(entry)) {
                    original = in.readAllBytes();
                }
                byte[] patched = soften(original);
                if (patched != null && !java.util.Arrays.equals(original, patched)) {
                    rewritten.put(name, patched);
                }
            }
        }
        if (rewritten.isEmpty()) {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return 0;
        }
        byte[] manifest = stripDigests(readEntry(source, "META-INF/MANIFEST.MF"), rewritten.keySet());
        try (ZipFile zip = new ZipFile(source);
                JarOutputStream out = new JarOutputStream(Files.newOutputStream(target.toPath()))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data;
                String name = entry.getName();

                // A signed jar's .SF lists every signed entry and its .RSA carries the signature
                // over that list. Rewriting an entry invalidates both, and the JVM then refuses to
                // READ that entry at all - "no manifest section for signature file entry <config>",
                // which surfaces as the very misleading "Error parsing or using Mixin config". Any
                // jar we modify is therefore copied unsigned. Nothing is actually lost: the
                // signature only attests the publisher, and we have deliberately changed the bytes.
                if (isSignatureArtefact(name)) {
                    continue;
                }

                if (name.equals("META-INF/MANIFEST.MF") && manifest != null) {
                    data = manifest;
                } else if (rewritten.containsKey(name)) {
                    data = rewritten.get(name);
                } else {
                    try (InputStream in = zip.getInputStream(entry)) {
                        data = in.readAllBytes();
                    }
                }
                out.putNextEntry(new ZipEntry(name));
                out.write(data);
                out.closeEntry();
            }
        }
        return rewritten.size();
    }

    /** Jar signature artefacts: the signature file, its RSA/DSA carrier, and the digest index. */
    private static boolean isSignatureArtefact(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.equals("META-INF/INDEX.LIST");
    }

    private static byte[] readEntry(File jar, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry found = zip.getEntry(entry);
            if (found == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(found)) {
                return in.readAllBytes();
            }
        }
    }

    /**
     * Removes the manifest's per-entry digest section for the entries we rewrote.
     *
     * <p>Signed/verified jars carry a {@code SHA-384-Digest} per file in the manifest, and the
     * JVM refuses to read any entry whose digest does not match - so changing a mixin config
     * without updating the manifest turns into
     * {@code SecurityException: SHA-384 digest error for <config>}, which surfaces as the very
     * misleading "Error parsing or using Mixin config". Rather than recompute digests for the whole
     * archive we simply drop the digests of the entries we touched; entries we did not modify keep
     * theirs, so the archive stays as verifiable as it needs to be.
     */
    private static byte[] stripDigests(byte[] manifest, java.util.Set<String> rewritten) {
        if (manifest == null) {
            return null;
        }
        // Manifest line breaks are CRLF (occasionally bare LF); keep whatever the jar used.
        String text = new String(manifest, StandardCharsets.UTF_8);
        List<String> kept = new ArrayList<>();
        boolean dropping = false;
        for (String line : text.split("\r\n|\n", -1)) {
            if (line.startsWith("Name: ")) {
                String name = line.substring("Name: ".length()).trim();
                dropping = rewritten.contains(name);
            } else if (line.trim().isEmpty()) {
                dropping = false;
            }
            if (dropping && line.startsWith("SHA-") || dropping && line.startsWith("MD5-Digest")) {
                continue;
            }
            kept.add(line);
        }
        String joined = String.join("\r\n", kept);
        return joined.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Rewrites one mixin config to tolerate failed injections.
     *
     * <p>Deliberately a targeted textual edit rather than a JSON parse-and-reserialise: re-emitting
     * the document would reorder keys and drop formatting, which makes the difference between the
     * operator's jar and our copy hard to review. Two precise replacements touch only what the
     * policy changes.
     *
     * @return the patched bytes, or null if this is not a mixin config we should touch
     */
    static byte[] soften(byte[] json) {
        String text = new String(json, StandardCharsets.UTF_8);

        // Must look like a mixin config: has a "package" and at least one mixin list.
        if (!text.contains("\"package\"") || !text.contains("\"mixins\"")) {
            return null;
        }
        // NOTE: a config with its own "plugin" is still relaxed on purpose. A MixinConfigPlugin
        // controls WHICH mixins apply (shouldApplyMixin / getMixins); it does not change the
        // require threshold that turns a failed injection into a fatal InjectionError. Skipping
        // plugin-bearing configs would exempt exactly the large, sophisticated mods most likely to
        // hit a CraftBukkit-reshaped anchor - vmp is one - which is precisely backwards. The plugin
        // keeps full control over selection; we only relax fatality.

        // Each edit is applied to the ORIGINAL text and collected as (start, end, replacement), then
        // applied once at the end. Editing in place and re-running a match over the already-edited
        // string makes the second matcher's offsets refer to the wrong text and silently deletes the
        // text between them - which is exactly what this did before it was tested.
        List<int[]> edits = new ArrayList<>();
        List<String> replacements = new ArrayList<>();

        // 1. "required": true -> false
        java.util.regex.Matcher req = java.util.regex.Pattern
                .compile("(\"required\"\\s*:\\s*)true").matcher(text);
        if (req.find()) {
            edits.add(new int[]{req.start(), req.end()});
            replacements.add(req.group(1) + "false");
        }

        // 2. "defaultRequire": N -> 0
        java.util.regex.Matcher def = java.util.regex.Pattern
                .compile("(\"defaultRequire\"\\s*:\\s*)\\d+").matcher(text);
        boolean hadDefaultRequire = def.find();
        if (hadDefaultRequire) {
            edits.add(new int[]{def.start(), def.end()});
            replacements.add(def.group(1) + "0");
        }

        // 3. No "defaultRequire" anywhere: inject an injectors block. Without it the config's own
        // default of 1 keeps injections fatal, so this is the case that actually decides whether
        // the policy did anything. (Checked via the find() result above - calling group() on a
        // never-matched matcher throws IllegalStateException("No match found").)
        if (!hadDefaultRequire) {
            java.util.regex.Matcher anchor = java.util.regex.Pattern
                    .compile("\"required\"\\s*:\\s*(?:true|false)\\s*,?").matcher(text);
            if (!anchor.find()) {
                return null;
            }
            int at = anchor.end();
            edits.add(new int[]{at, at});
            String lead = "\n  ";
            replacements.add(lead + "\"injectors\": { \"defaultRequire\": 0 },"
                    + (text.charAt(at - 1) == ',' ? "" : ","));
        }

        if (edits.isEmpty()) {
            return null;
        }
        edits.sort((a, b) -> Integer.compare(a[0], b[0]));
        StringBuilder out = new StringBuilder(text.length() + 64);
        int cursor = 0;
        for (int i = 0; i < edits.size(); i++) {
            out.append(text, cursor, edits.get(i)[0]);
            out.append(replacements.get(i));
            cursor = edits.get(i)[1];
        }
        out.append(text, cursor, text.length());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not delete stale shadow file " + file);
        }
    }
}
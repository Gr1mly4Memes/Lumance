/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/**
 * Fetches the pinned NeoForm artifact (decompile fixup patches) from
 * NeoForge's maven. Never vendored: the version in gradle.properties is the
 * only reference, resolved here at build time.
 */
public abstract class FetchNeoFormTask extends DefaultTask {
    @Input
    public abstract Property<String> getNeoformVersion();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @TaskAction
    public void run() throws Exception {
        String version = getNeoformVersion().get();
        File outputDir = getOutputDir().getAsFile().get();
        File marker = new File(outputDir, ".neoform-version");
        // Not every NeoForm ships side-specific fixups; the empty dir keeps
        // consumers' directory inputs valid either way.
        new File(outputDir, "client-patches").mkdirs();
        if (marker.exists() && version.equals(Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim())) {
            getLogger().lifecycle("[Lumance] NeoForm {} already fetched", version);
            return;
        }
        String url = "https://maven.neoforged.net/releases/net/neoforged/neoform/"
                + version + "/neoform-" + version + ".zip";
        getLogger().lifecycle("[Lumance] Fetching NeoForm {} ...", version);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET " + url + " -> HTTP " + response.statusCode());
        }
        File zipFile = new File(outputDir.getParentFile(), "neoform-" + version + ".zip");
        zipFile.getParentFile().mkdirs();
        try (InputStream in = response.body(); FileOutputStream out = new FileOutputStream(zipFile)) {
            in.transferTo(out);
        }
        deleteRecursively(outputDir);
        outputDir.mkdirs();
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File target = new File(outputDir, entry.getName());
                if (entry.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                target.getParentFile().mkdirs();
                try (InputStream in = zip.getInputStream(entry); FileOutputStream out = new FileOutputStream(target)) {
                    in.transferTo(out);
                }
            }
        }
        zipFile.delete();
        new File(outputDir, "client-patches").mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(marker), StandardCharsets.UTF_8)) {
            writer.write(version + "\n");
        }
        getLogger().lifecycle("[Lumance] NeoForm {} ready", version);
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

/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.time.Duration;
import java.util.Properties;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Resolves the vanilla artifacts through Mojang's version manifest: finds the
 * pinned game version, fetches its version JSON and records the server bundle
 * (plus client jar) coordinates for the download tasks.
 */
public abstract class ResolveVanillaTask extends DefaultTask {
    @Input
    public abstract Property<String> getMcVersion();

    @Input
    public abstract Property<String> getManifestUrl();

    @Input
    @Optional
    public abstract Property<String> getBundleUrlOverride();

    @Input
    @Optional
    public abstract Property<String> getBundleSha1Override();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    @OutputFile
    public abstract RegularFileProperty getVersionJson();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @TaskAction
    public void run() throws Exception {
        String mcVersion = getMcVersion().get();
        getLogger().lifecycle("[Lumance] Resolving Minecraft {} from {}", mcVersion, getManifestUrl().get());
        JsonObject manifest = fetchJson(getManifestUrl().get());
        String versionUrl = null;
        for (var element : manifest.getAsJsonArray("versions")) {
            JsonObject entry = element.getAsJsonObject();
            if (mcVersion.equals(entry.get("id").getAsString())) {
                versionUrl = entry.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IllegalStateException("Minecraft " + mcVersion + " not found in the version manifest");
        }
        JsonObject version = fetchJson(versionUrl);
        File versionJsonFile = getVersionJson().getAsFile().get();
        versionJsonFile.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(versionJsonFile), StandardCharsets.UTF_8)) {
            writer.write(version.toString());
        }

        JsonObject server = version.getAsJsonObject("downloads").getAsJsonObject("server");
        JsonObject client = version.getAsJsonObject("downloads").getAsJsonObject("client");
        String bundleUrl = getBundleUrlOverride().getOrElse("");
        String bundleSha1 = getBundleSha1Override().getOrElse("");
        if (bundleUrl.isEmpty()) {
            bundleUrl = server.get("url").getAsString();
        }
        if (bundleSha1.isEmpty()) {
            bundleSha1 = server.get("sha1").getAsString();
        }
        getLogger().lifecycle("[Lumance] Vanilla bundle: {} (sha1 {})", bundleUrl, bundleSha1);

        Properties props = new Properties();
        props.setProperty("minecraft.version", mcVersion);
        props.setProperty("bundle.url", bundleUrl);
        props.setProperty("bundle.sha1", bundleSha1);
        props.setProperty("bundle.size", server.has("size") ? server.get("size").getAsString() : "");
        props.setProperty("client.url", client.get("url").getAsString());
        props.setProperty("client.sha1", client.get("sha1").getAsString());
        props.setProperty("client.size", client.has("size") ? client.get("size").getAsString() : "");
        File output = getOutput().getAsFile().get();
        output.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8)) {
            props.store(writer, null);
        }
    }

    private static JsonObject fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET " + url + " -> HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}

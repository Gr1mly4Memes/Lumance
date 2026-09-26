/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import com.google.gson.JsonElement;
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
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Downloads the client libraries from Mojang (every entry's plain artifact)
 * and records them in the client libs index. Rules are deliberately ignored:
 * even OS-gated entries like the mac bridge ship real classes the client
 * sources import, and this track is compiled, never run - so native
 * classifiers are skipped entirely.
 */
public abstract class ResolveClientLibrariesTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getVersionJson();

    @OutputDirectory
    public abstract DirectoryProperty getLibsDir();

    @OutputFile
    public abstract RegularFileProperty getLibsIndex();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @TaskAction
    public void run() throws Exception {
        JsonObject version = JsonParser.parseString(
                Files.readString(getVersionJson().getAsFile().get().toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        File libsDir = getLibsDir().getAsFile().get();
        List<String> index = new ArrayList<>();
        int count = 0;
        for (JsonElement element : version.getAsJsonArray("libraries")) {
            JsonObject lib = element.getAsJsonObject();
            JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            String path = artifact.get("path").getAsString();
            String sha1 = artifact.get("sha1").getAsString();
            File target = new File(libsDir, path.replace('/', File.separatorChar));
            if (!(target.exists() && Objects.equals(Hashes.sha1(target), sha1))) {
                target.getParentFile().mkdirs();
                File part = new File(target.getParentFile(), target.getName() + ".part");
                String url = artifact.get("url").getAsString();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("GET " + url + " -> HTTP " + response.statusCode());
                }
                try (InputStream in = response.body(); FileOutputStream out = new FileOutputStream(part)) {
                    in.transferTo(out);
                }
                if (!Objects.equals(Hashes.sha1(part), sha1)) {
                    part.delete();
                    throw new IllegalStateException("SHA-1 mismatch downloading " + url);
                }
                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("Could not replace " + target);
                }
                if (!part.renameTo(target)) {
                    throw new IllegalStateException("Could not move " + part + " to " + target);
                }
            }
            index.add(path + "\t" + Hashes.sha256(target) + "\t" + target.length());
            count++;
        }
        getLogger().lifecycle("[Lumance] Client libraries: {} jars", count);
        File libsIndex = getLibsIndex().getAsFile().get();
        libsIndex.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(libsIndex), StandardCharsets.UTF_8)) {
            for (String line : index) {
                writer.write(line + "\n");
            }
        }
    }
}

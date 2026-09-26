/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package lumance.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Downloads one pinned artifact (bundle, client jar) and verifies its SHA-1. */
public abstract class DownloadTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getPropsFile();

    @Input
    public abstract Property<String> getUrlKey();

    @Input
    public abstract Property<String> getSha1Key();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @TaskAction
    public void run() throws Exception {
        Properties props = new Properties();
        try (Reader reader = new InputStreamReader(new FileInputStream(getPropsFile().getAsFile().get()), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        String url = props.getProperty(getUrlKey().get());
        String sha1 = props.getProperty(getSha1Key().get(), "");
        File output = getOutput().getAsFile().get();
        if (output.exists() && (sha1.isEmpty() || Objects.equals(Hashes.sha1(output), sha1))) {
            getLogger().lifecycle("[Lumance] Reusing {}", output.getName());
            return;
        }
        output.getParentFile().mkdirs();
        File part = new File(output.getParentFile(), output.getName() + ".part");
        getLogger().lifecycle("[Lumance] Downloading {} ...", url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET " + url + " -> HTTP " + response.statusCode());
        }
        try (InputStream in = response.body(); FileOutputStream out = new FileOutputStream(part)) {
            in.transferTo(out);
        }
        if (!sha1.isEmpty() && !Objects.equals(Hashes.sha1(part), sha1)) {
            part.delete();
            throw new IllegalStateException("SHA-1 mismatch downloading " + url);
        }
        if (output.exists() && !output.delete()) {
            throw new IllegalStateException("Could not replace " + output);
        }
        if (!part.renameTo(output)) {
            throw new IllegalStateException("Could not move " + part + " to " + output);
        }
        getLogger().lifecycle("[Lumance] Downloaded {} ({} bytes)", output.getName(), output.length());
    }
}

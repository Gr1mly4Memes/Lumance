/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.launcher.lumance.step;

import gr1mly4memes.launcher.lumance.util.Progress;
import gr1mly4memes.launcher.lumance.util.Sha1;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Downloads files once and verifies their hashes. Cached files with a matching
 * hash are reused. Batches share one progress line (plus byte detail for files
 * over 1 MB) instead of logging every file twice.
 */
public final class Download {
    private Download() {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    /** Show byte detail only once a download is this big; small files just tick the bar. */
    private static final long BYTE_DETAIL_THRESHOLD = 1 << 20;

    /** One file to fetch: its URL, hash and target. */
    public record Entry(String url, String expectedHash, String algorithm, File target) {
    }

    public static void ensure(String url, String expectedHash, String algorithm, File target) throws Exception {
        ensureAll(List.of(new Entry(url, expectedHash, algorithm, target)), "Downloading " + target.getName(), "file");
    }

    /**
     * Ensures every entry is present and verified, with a single progress line.
     * Files already matching their hash are reused silently and counted.
     */
    public static void ensureAll(List<Entry> entries, String label, String noun) throws Exception {
        Progress progress = new Progress(label, entries.size());
        int fresh = 0;
        for (Entry entry : entries) {
            if (entry.target().exists() && (entry.expectedHash().isEmpty()
                    || Objects.equals(Sha1.hash(entry.target(), entry.algorithm()), entry.expectedHash()))) {
                progress.step();
                continue;
            }
            download(entry, progress);
            fresh++;
            progress.step();
        }
        String nounCap = noun.isEmpty() ? noun : Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
        if (fresh == 0) {
            progress.finish(nounCap + " up to date (" + entries.size() + " verified)");
        } else {
            progress.finish(nounCap + " ready (" + entries.size() + " verified, " + fresh + " downloaded)");
        }
    }

    private static void download(Entry entry, Progress progress) throws Exception {
        entry.target().getParentFile().mkdirs();
        File part = new File(entry.target().getParentFile(), entry.target().getName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(entry.url())).timeout(Duration.ofMinutes(30)).GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET " + entry.url() + " -> HTTP " + response.statusCode());
        }
        long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        long total = 0;
        long lastDetail = 0;
        boolean showBytes = length >= BYTE_DETAIL_THRESHOLD;
        try (InputStream in = response.body(); OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
                if (!showBytes && total >= BYTE_DETAIL_THRESHOLD) {
                    showBytes = true;
                }
                long now = System.nanoTime();
                if (showBytes && now - lastDetail >= 100_000_000L) {
                    lastDetail = now;
                    progress.detail(byteDetail(entry.target().getName(), total, length));
                }
            }
        }
        if (!entry.expectedHash().isEmpty() && !Objects.equals(Sha1.hash(part, entry.algorithm()), entry.expectedHash())) {
            part.delete();
            throw new IllegalStateException("Hash mismatch downloading " + entry.url());
        }
        if (entry.target().exists() && !entry.target().delete()) {
            throw new IllegalStateException("Could not replace " + entry.target());
        }
        if (!part.renameTo(entry.target())) {
            throw new IllegalStateException("Could not move " + part + " to " + entry.target());
        }
    }

    private static String byteDetail(String name, long done, long length) {
        if (length > 0) {
            int percent = (int) (done * 100 / length);
            return name + " " + percent + "% (" + megabytes(done) + "/" + megabytes(length) + " MB)";
        }
        return name + " (" + megabytes(done) + " MB)";
    }

    private static String megabytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0);
    }
}

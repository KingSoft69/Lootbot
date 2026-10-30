package dev.lootbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Talks to the learner over HTTP, never on the tick thread: every call returns a future and the
 * job looks at it on a later tick. Episodes that cannot be sent wait in an outbox file next to the
 * config and go out with the next successful call.
 */
final class LearnerClient {

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** What the learner hands out: a target with the settings to try on it, or nothing to do. */
    static final class Work {
        Target target;
        Params params;
        String paramsId;
        /** The plugin version this bot should run; null or equal to ours: no change. */
        String version;
        Jar jar;
        /** Seconds to wait before asking again when there is no target. */
        int retrySeconds;
        String note;
    }

    static final class Target {
        long id;
        int x;
        Integer y;
        int z;
        String dim = "overworld";
        String label;
    }

    static final class Jar {
        String url;
        String sha256;
    }

    private final Supplier<LootConfig.Learner> config;
    private final Path outbox;
    private final Consumer<String> log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Object outboxLock = new Object();

    LearnerClient(Supplier<LootConfig.Learner> config, Path outbox, Consumer<String> log) {
        this.config = config;
        this.outbox = outbox;
        this.log = log;
    }

    boolean configured() {
        String u = config.get().url;
        return u != null && !u.isBlank();
    }

    private HttpRequest.Builder request(String path) {
        String base = config.get().url.replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + config.get().token)
                .header("Content-Type", "application/json");
    }

    /** Asks for the next target. Completes with null when the learner cannot be reached. */
    CompletableFuture<Work> next(String bot, String version, JsonObject state) {
        JsonObject body = new JsonObject();
        body.addProperty("bot", bot);
        body.addProperty("version", version);
        body.add("state", state);
        CompletableFuture.runAsync(this::flushOutbox); // it waits on each send: never on the tick thread
        return http.sendAsync(request("/api/next").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    if (r.statusCode() != 200) {
                        log.accept("LootBot: learner answered " + r.statusCode() + " to next: " + shorten(r.body()));
                        return null;
                    }
                    return GSON.fromJson(r.body(), Work.class);
                })
                .exceptionally(e -> {
                    log.accept("LootBot: learner not reachable (" + e.getMessage() + ")");
                    return null;
                });
    }

    /** Sends a finished episode; when that fails it goes to the outbox. */
    void report(JsonObject episode) {
        String json = GSON.toJson(episode);
        post("/api/episode", json).thenAccept(ok -> {
            if (!ok) {
                toOutbox(json);
            }
        });
    }

    /** The dashboard's live view. Best effort: a lost one is replaced a second later. */
    void view(JsonObject view) {
        post("/api/view", GSON.toJson(view));
    }

    /** The bot's eyes: a pose, sometimes with the blocks around it. Best effort. */
    void eyes(JsonObject msg) {
        post("/api/eyes", GSON.toJson(msg));
    }

    /** A short event the learner keeps in its log (a find, a problem). Best effort, never queued. */
    void event(String bot, String kind, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("bot", bot);
        o.addProperty("kind", kind);
        o.addProperty("text", text);
        post("/api/event", GSON.toJson(o));
    }

    private CompletableFuture<Boolean> post(String path, String json) {
        if (!configured()) {
            return CompletableFuture.completedFuture(false);
        }
        return http.sendAsync(request(path).POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> r.statusCode() == 200)
                .exceptionally(e -> false);
    }

    private void toOutbox(String json) {
        synchronized (outboxLock) {
            try {
                Files.writeString(outbox, json.replace('\n', ' ') + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.accept("LootBot: could not keep an unsent episode: " + e.getMessage());
            }
        }
    }

    /** Sends what waits in the outbox, one at a time; whatever fails stays. */
    private final java.util.concurrent.atomic.AtomicBoolean flushing = new java.util.concurrent.atomic.AtomicBoolean();

    private void flushOutbox() {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            flushOutboxOnce();
        } finally {
            flushing.set(false);
        }
    }

    private void flushOutboxOnce() {
        List<String> lines;
        synchronized (outboxLock) {
            if (!Files.exists(outbox)) {
                return;
            }
            try {
                lines = Files.readAllLines(outbox, StandardCharsets.UTF_8);
                Files.delete(outbox);
            } catch (IOException e) {
                return;
            }
        }
        List<String> keep = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                if (!post("/api/episode", line).get()) {
                    keep.add(line);
                }
            } catch (Exception e) {
                keep.add(line);
            }
        }
        for (String line : keep) {
            toOutbox(line);
        }
        if (!lines.isEmpty() && keep.isEmpty()) {
            log.accept("LootBot: sent " + lines.size() + " episode(s) that were waiting");
        }
    }

    /**
     * Downloads a plugin jar to {@code dest}, checking its SHA-256. Completes with true only when
     * the file is complete and matches.
     */
    CompletableFuture<Boolean> download(Jar jar, Path dest) {
        String url = jar.url.startsWith("http") ? jar.url : config.get().url.replaceAll("/+$", "") + jar.url;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + config.get().token)
                .GET().build();
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofFile(part))
                .thenApply(r -> {
                    try {
                        if (r.statusCode() != 200) {
                            Files.deleteIfExists(part);
                            log.accept("LootBot: jar download answered " + r.statusCode());
                            return false;
                        }
                        String sum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(part)));
                        if (jar.sha256 == null || !sum.equalsIgnoreCase(jar.sha256.trim())) {
                            Files.deleteIfExists(part);
                            log.accept("LootBot: downloaded jar failed its SHA-256 check");
                            return false;
                        }
                        Files.move(part, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        return true;
                    } catch (Exception e) {
                        log.accept("LootBot: jar download failed: " + e.getMessage());
                        return false;
                    }
                })
                .exceptionally(e -> {
                    log.accept("LootBot: jar download failed: " + e.getMessage());
                    return false;
                });
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}

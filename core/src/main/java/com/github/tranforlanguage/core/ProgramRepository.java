package com.github.tranforlanguage.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A program directory managed as a Git working tree. The configured branch (or the remote's
 * default branch) is fetched and reset into the directory, keeping the local program.json
 * settings that describe how the program is hosted.
 */
final class ProgramRepository {
    static final long DEFAULT_INTERVAL_SECONDS = 3600;
    private static final String READY_MARKER = ".tranforlanguage/repository-ready";
    private static final int INIT_TIMEOUT_SECONDS = 60;
    private static final int FETCH_TIMEOUT_SECONDS = 120;
    private static final int SHORT_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_BYTES = 1 << 20;
    private static final Gson PLAIN = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern CREDENTIALS = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\\s]+@");

    final String url;
    final String branch;
    final long intervalSeconds;

    private ProgramRepository(String url, String branch, long intervalSeconds) {
        this.url = url;
        this.branch = branch;
        this.intervalSeconds = intervalSeconds;
    }

    /** Reads the optional GitHub/GitLab/Git settings; null when this program is not managed by Git. */
    static ProgramRepository parse(JsonObject configuration) {
        if (configuration == null || !configuration.has("repository")) return null;
        JsonElement element = configuration.get("repository");
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("repository must be a Git URL string");
        String url = element.getAsString().trim();
        if (url.isEmpty()) throw new IllegalArgumentException("repository must not be empty");
        if (url.startsWith("-")) throw new IllegalArgumentException("repository must not start with '-'");
        String branch = null;
        if (configuration.has("branch")) {
            JsonElement value = configuration.get("branch");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("branch must be a string");
            branch = value.getAsString().trim();
            if (branch.isEmpty() || branch.startsWith("-"))
                throw new IllegalArgumentException("branch must be a non-empty branch name");
        }
        long interval = DEFAULT_INTERVAL_SECONDS;
        if (configuration.has("updateInterval")) {
            JsonElement value = configuration.get("updateInterval");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("updateInterval must be a number of seconds (0 disables automatic updates)");
            double seconds = value.getAsDouble();
            if (!Double.isFinite(seconds) || seconds < 0 || seconds != Math.rint(seconds) || seconds > Long.MAX_VALUE)
                throw new IllegalArgumentException("updateInterval must be a non-negative whole number of seconds");
            interval = (long) seconds;
        }
        return new ProgramRepository(url, branch, interval);
    }

    static boolean checkedOut(File directory) {
        return new File(directory, ".git").exists();
    }

    /** A successful checkout marker; .git alone is not enough after a failed first fetch. */
    static boolean ready(File directory) {
        return new File(directory, READY_MARKER).isFile();
    }

    private static void markReady(File directory) throws IOException {
        File marker = new File(directory, READY_MARKER);
        Files.createDirectories(marker.getParentFile().toPath());
        Files.write(marker.toPath(), new byte[0]);
    }

    private static void clearReady(File directory) {
        try { Files.deleteIfExists(new File(directory, READY_MARKER).toPath()); }
        catch (IOException ignored) { }
    }

    /** The URL without any embedded credentials, for log messages. */
    String displayUrl() {
        return redact(url);
    }

    /**
     * Fetches the configured ref and resets the working tree when the commit changed.
     *
     * @return true when the working tree was replaced with a different commit
     */
    boolean sync(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create program directory: " + directory);
        File manifest = new File(directory, "program.json");
        byte[] localManifest = manifest.isFile() ? Files.readAllBytes(manifest.toPath()) : new byte[0];
        JsonObject local = parseManifest(localManifest);
        // Pick up repository/branch edits from program.json without waiting for a restart.
        ProgramRepository current = parse(local);
        if (current == null) return false;
        String activeUrl = current.url;
        String activeBranch = current.branch;

        if (!checkedOut(directory)) {
            run(directory, INIT_TIMEOUT_SECONDS, "init", "-q");
            run(directory, SHORT_TIMEOUT_SECONDS, "remote", "add", "origin", activeUrl);
        } else if (hasRemote(directory, "origin")) {
            run(directory, SHORT_TIMEOUT_SECONDS, "remote", "set-url", "origin", activeUrl);
        } else {
            run(directory, SHORT_TIMEOUT_SECONDS, "remote", "add", "origin", activeUrl);
        }

        String previous = head(directory);
        run(directory, FETCH_TIMEOUT_SECONDS, "fetch", "--force", "--prune", "origin", activeBranch == null ? "HEAD" : activeBranch);
        String target = run(directory, SHORT_TIMEOUT_SECONDS, "rev-parse", "FETCH_HEAD^{commit}").trim();
        if (target.isEmpty()) throw new IOException("git fetch did not report a commit");
        if (target.equals(previous) && ready(directory)) return false;

        boolean manifestInRepository = !run(directory, SHORT_TIMEOUT_SECONDS,
            "ls-tree", "--name-only", target, "--", "program.json").trim().isEmpty();
        if (manifestInRepository) {
            // Validate the incoming manifest before touching the working tree; a broken upstream
            // program.json must not make every later fetch fail on the local copy.
            parseManifest(run(directory, SHORT_TIMEOUT_SECONDS, "show", target + ":program.json")
                .getBytes(StandardCharsets.UTF_8));
        }
        // reset --hard updates every tracked file; the merged manifest is written back afterwards.
        clearReady(directory);
        try {
            run(directory, SHORT_TIMEOUT_SECONDS, "reset", "--hard", target);
            restoreManifest(directory, localManifest, local, activeUrl, activeBranch, manifestInRepository);
        } catch (IOException failure) {
            if (localManifest.length != 0) try { Files.write(manifest.toPath(), localManifest); }
            catch (IOException ignored) { }
            throw failure;
        }
        markReady(directory);
        return true;
    }

    private boolean hasRemote(File directory, String name) throws IOException {
        for (String remote : run(directory, SHORT_TIMEOUT_SECONDS, "remote").split("\\r?\\n"))
            if (remote.trim().equals(name)) return true;
        return false;
    }

    /** The current commit, or null before the first checkout (an unborn HEAD). */
    private String head(File directory) {
        try {
            return run(directory, SHORT_TIMEOUT_SECONDS, "rev-parse", "--verify", "HEAD").trim();
        } catch (IOException unborn) {
            return null;
        }
    }

    private static JsonObject parseManifest(byte[] bytes) throws IOException {
        if (bytes.length == 0) return new JsonObject();
        try {
            JsonObject parsed = PLAIN.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            return parsed == null ? new JsonObject() : parsed;
        } catch (JsonParseException invalid) {
            throw new IOException("Invalid program.json: " + invalid.getMessage(), invalid);
        }
    }

    private void restoreManifest(File directory, byte[] localBytes, JsonObject local,
                                 String activeUrl, String activeBranch, boolean inRepository) throws IOException {
        File manifest = new File(directory, "program.json");
        if (!inRepository) {
            // reset only removes files that were tracked before; restore a local-only manifest it may have removed.
            if (localBytes.length != 0) Files.write(manifest.toPath(), localBytes);
            return;
        }
        JsonObject merged;
        try (Reader reader = Files.newBufferedReader(manifest.toPath(), StandardCharsets.UTF_8)) {
            merged = PLAIN.fromJson(reader, JsonObject.class);
        } catch (JsonParseException invalid) {
            throw new IOException("Repository program.json is invalid: " + invalid.getMessage(), invalid);
        }
        if (merged == null) merged = new JsonObject();
        // When the repository ships its own manifest it owns the program settings; the installation
        // only contributes the three update settings.
        merged.addProperty("repository", activeUrl);
        merged.remove("branch");
        if (activeBranch != null) merged.addProperty("branch", activeBranch);
        merged.remove("updateInterval");
        if (local.has("updateInterval")) merged.add("updateInterval", local.get("updateInterval"));
        Files.write(manifest.toPath(), (PRETTY.toJson(merged) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    private static String run(File directory, int timeoutSeconds, String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("tranforlanguage.git", "git"));
        command.addAll(Arrays.asList(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command).directory(directory)
                .redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            throw new IOException("Cannot run git: " + unavailable.getMessage(), unavailable);
        }
        process.getOutputStream().close();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream stream = process.getInputStream()) {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    int room = MAX_OUTPUT_BYTES - output.size();
                    if (room > 0) output.write(buffer, 0, Math.min(count, room));
                }
            } catch (IOException ignored) { }
        }, "TranForLanguage-Git");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git " + arguments[0], interrupted);
        }
        if (!finished) {
            process.destroyForcibly();
            try { reader.join(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw new IOException("git " + arguments[0] + " timed out after " + timeoutSeconds + "s");
        }
        try { reader.join(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            String detail = redact(text).trim();
            if (detail.length() > 4000) detail = detail.substring(0, 4000) + "...";
            throw new IOException("git " + arguments[0] + " failed" + (detail.isEmpty() ? "" : ": " + detail));
        }
        return text;
    }

    private static String redact(String text) {
        return CREDENTIALS.matcher(text).replaceAll("$1***@");
    }
}

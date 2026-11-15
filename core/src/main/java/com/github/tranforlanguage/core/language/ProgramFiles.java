package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Managed SDK resources and tool invocations, shared by language adapters. */
final class ProgramFiles {
    private ProgramFiles() {}
    static File sdk(File directory, String resource) throws IOException {
        return resource(directory, "/sdk/" + resource, resource);
    }
    static File resource(File directory, String resource, String name) throws IOException {
        File target = new File(directory, ".tranforlanguage/sdk/" + name);
        Files.createDirectories(target.toPath().getParent());
        try (InputStream input = ProgramFiles.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing bundled SDK: " + resource);
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return target.getAbsoluteFile();
    }
    /** Top-level files of the program directory with the extension, sorted for reproducible builds. */
    static File[] sources(File directory, String extension) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(extension) && new File(dir, name).isFile());
        if (files == null) files = new File[0];
        java.util.Arrays.sort(files);
        return files;
    }
    /** A tool next to the running JVM (javac/java) unless overridden or absent. */
    static String jdkTool(String property, String tool) {
        String configured = System.getProperty(property);
        if (configured != null) return configured;
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        File bundled = new File(System.getProperty("java.home"), "bin/" + tool + (windows ? ".exe" : ""));
        return bundled.isFile() ? bundled.getAbsolutePath() : tool;
    }
    static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(file.toPath())) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
    static File entrypoint(File directory, JsonObject config, String fallback) throws IOException {
        String name = fallback;
        if (config.has("entrypoint")) {
            if (!config.get("entrypoint").isJsonPrimitive() || !config.getAsJsonPrimitive("entrypoint").isString())
                throw new IllegalArgumentException("entrypoint must be a string");
            name = config.get("entrypoint").getAsString();
        }
        File source = new File(directory, name).getCanonicalFile();
        if (!source.toPath().startsWith(directory.getCanonicalFile().toPath()) || !source.isFile())
            throw new IOException("Missing program entrypoint inside program directory: " + name);
        return source;
    }
    /** A manifest-driven, isolated dependency cache. A failed install never leaves a valid stamp. */
    interface Setup { void run() throws Exception; }
    static void cached(File cache, String identity, Setup setup, File... inputs) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(identity.getBytes(StandardCharsets.UTF_8));
        for (File input : inputs) {
            digest.update(input.getName().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) (input.isFile() ? 1 : 0));
            if (input.isFile()) digest.update(Files.readAllBytes(input.toPath()));
        }
        byte[] value = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : value) hex.append(String.format("%02x", b & 0xff));
        File stamp = new File(cache, ".dependencies-sha256");
        if (stamp.isFile() && hex.toString().equals(new String(Files.readAllBytes(stamp.toPath()), StandardCharsets.UTF_8))) return;
        deleteTree(cache);
        Files.createDirectories(cache.toPath());
        try {
            setup.run();
            Files.write(stamp.toPath(), hex.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception failed) {
            Files.deleteIfExists(stamp.toPath());
            throw failed;
        }
    }
    /** Run a metadata tool without a shell (for instance pkg-config). */
    static String output(File directory, List<String> command, int seconds) throws IOException, InterruptedException {
        Path result = Files.createTempFile("tranforlanguage-tool-", ".log");
        try {
            Process process = new ProcessBuilder(command).directory(directory)
                .redirectErrorStream(true).redirectOutput(result.toFile()).start();
            try {
                if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new IOException("Tool timed out: " + command.get(0));
                String value = new String(Files.readAllBytes(result), StandardCharsets.UTF_8).trim();
                if (process.exitValue() != 0) throw new IOException(command.get(0) + " failed: " + value);
                return value;
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        } finally { Files.deleteIfExists(result); }
    }
    /** Parse the shell-style quoting emitted by pkg-config (never invoke a shell). */
    static List<String> flags(String value) throws IOException {
        List<String> args = new java.util.ArrayList<>();
        StringBuilder part = new StringBuilder();
        char quote = 0; boolean escape = false; boolean started = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escape) { part.append(ch); escape = false; started = true; }
            else if (ch == '\\' && quote != '\'') { escape = true; started = true; }
            else if (quote != 0) {
                if (ch == quote) quote = 0;
                else part.append(ch);
            } else if (ch == '\'' || ch == '"') { quote = ch; started = true; }
            else if (Character.isWhitespace(ch)) {
                if (started) { args.add(part.toString()); part.setLength(0); started = false; }
            } else { part.append(ch); started = true; }
        }
        if (escape || quote != 0) throw new IOException("Malformed tool flags");
        if (started) args.add(part.toString());
        return args;
    }
    static void build(File directory, List<String> command, int seconds) throws IOException, InterruptedException {
        build(directory, directory, command, java.util.Collections.<String, String>emptyMap(), seconds);
    }
    /** Runs a build tool in {@code workdir}; output always goes to the program's compile.log. */
    static void build(File directory, File workdir, List<String> command, java.util.Map<String, String> environment, int seconds)
        throws IOException, InterruptedException {
        File log = new File(directory, "compile.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(workdir).redirectErrorStream(true).redirectOutput(log);
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new IOException("Compilation timed out; see " + log);
            if (process.exitValue() != 0) throw new IOException("Compilation failed; see " + log);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}

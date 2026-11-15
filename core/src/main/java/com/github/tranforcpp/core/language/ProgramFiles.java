package com.github.tranforcpp.core.language;

import com.google.gson.JsonObject;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Managed SDK resources and tool invocations, shared by language adapters. */
final class ProgramFiles {
    private ProgramFiles() {}
    static File sdk(File directory, String resource) throws IOException {
        return resource(directory, "/sdk/" + resource, resource);
    }
    static File resource(File directory, String resource, String name) throws IOException {
        File target = new File(directory, ".tranforcpp/sdk/" + name);
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

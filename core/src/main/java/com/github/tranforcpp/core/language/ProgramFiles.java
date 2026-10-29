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
        File log = new File(directory, "compile.log");
        Process process = new ProcessBuilder(command).directory(directory).redirectErrorStream(true).redirectOutput(log).start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new IOException("Compilation timed out; see " + log);
            if (process.exitValue() != 0) throw new IOException("Compilation failed; see " + log);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}

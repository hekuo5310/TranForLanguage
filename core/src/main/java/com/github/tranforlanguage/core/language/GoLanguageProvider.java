package com.github.tranforlanguage.core.language;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Go {@code package main} programs. Top-level .go files are copied into a managed module that
 * replaces {@code tranforlanguage} with the bundled SDK; user files and go.mod are never written.
 */
public final class GoLanguageProvider implements LanguageProvider {
    public String id() { return "go"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File[] sources = ProgramFiles.sources(directory, ".go");
        if (sources.length == 0) throw new IOException("Missing Go sources (*.go) in program directory");
        File sdk = ProgramFiles.sdk(directory, "go/go.mod").getParentFile();
        ProgramFiles.sdk(directory, "go/tranforlanguage.go");
        ProgramFiles.sdk(directory, "go/stdin_native.go");
        ProgramFiles.sdk(directory, "go/stdin_wasip1.go");
        File project = new File(directory, ".tranforlanguage/go-project");
        ProgramFiles.deleteTree(new File(project, "src"));
        File src = new File(project, "src"); Files.createDirectories(src.toPath());
        for (File source : sources) Files.copy(source.toPath(), new File(src, source.getName()).toPath());
        File userMod = new File(directory, "go.mod");
        File userSum = new File(directory, "go.sum");
        if (userMod.isFile()) {
            Files.copy(userMod.toPath(), new File(src, "go.mod").toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (userSum.isFile()) Files.copy(userSum.toPath(), new File(src, "go.sum").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            String mod = "module tranforlanguage-program\n\ngo 1.21\n";
            Files.write(new File(src, "go.mod").toPath(), mod.getBytes(StandardCharsets.UTF_8));
        }
        String go = System.getProperty("tranforlanguage.go", "go");
        // Use go's JSON representation, not regexes over go.mod syntax.
        // Local replacements are relative to the ORIGINAL manifest, not our copied build directory.
        if (userMod.isFile()) {
            String json = ProgramFiles.capture(directory, Arrays.asList(go, "mod", "edit", "-json"), 30, 1048576);
            JsonObject details = new Gson().fromJson(json, JsonObject.class);
            JsonArray replacements = details.has("Replace") && details.get("Replace").isJsonArray()
                ? details.getAsJsonArray("Replace") : null;
            if (replacements != null) for (JsonElement element : replacements) {
                JsonObject replacement = element.getAsJsonObject();
                JsonObject old = replacement.getAsJsonObject("Old");
                JsonObject target = replacement.getAsJsonObject("New");
                if (target.has("Version") && !target.get("Version").isJsonNull()
                    && !target.get("Version").getAsString().isEmpty()) continue;
                String location = target.get("Path").getAsString();
                if (!location.startsWith(".")) continue;
                String module = old.get("Path").getAsString();
                if (old.has("Version") && !old.get("Version").isJsonNull()
                    && !old.get("Version").getAsString().isEmpty())
                    module += "@" + old.get("Version").getAsString();
                String absolute = new File(directory, location).getCanonicalPath().replace(File.separatorChar, '/');
                ProgramFiles.build(directory, src, Arrays.asList(go, "mod", "edit",
                    "-replace=" + module + "=" + absolute), Collections.emptyMap(), 30);
            }
        }
        // The bundled SDK always overrides a user-supplied tranforlanguage replacement.
        ProgramFiles.build(directory, src, Arrays.asList(go, "mod", "edit",
            "-require=tranforlanguage@v0.0.0",
            "-replace=tranforlanguage=" + sdk.getAbsolutePath()), Collections.emptyMap(), 30);
        LaunchCommand jvm = InJvm.attempt(configuration, logger, "Go", () -> {
            File module = new File(project, "program.wasm");
            Map<String, String> wasm = environment();
            wasm.put("GOOS", "wasip1"); wasm.put("GOARCH", "wasm");
            build(directory, src, go, module, wasm);
            // The WASI build polls the server for input so goroutines keep running while it waits.
            return InJvm.wasm(directory, module, logger, true);
        });
        if (jvm != null) return jvm;
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        File executable = new File(project, windows ? "tranforlanguage_program.exe" : "tranforlanguage_program");
        build(directory, src, go, executable, environment());
        logger.info("Go compilation complete: " + sources.length + " source(s)");
        return new LaunchCommand(directory, Collections.singletonList(executable.getAbsolutePath()));
    }

    private static Map<String, String> environment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("GOWORK", "off"); environment.put("GOFLAGS", "-mod=mod"); environment.put("CGO_ENABLED", "0");
        return environment;
    }

    private static void build(File directory, File src, String go, File output, Map<String, String> environment) throws Exception {
        Files.deleteIfExists(output.toPath());
        ProgramFiles.build(directory, src, Arrays.asList(go, "build", "-o", output.getAbsolutePath(), "."), environment, 300);
        if (!output.isFile()) throw new IOException("Go did not produce the program; see compile.log");
    }
}

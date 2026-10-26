package com.github.tranforcpp.core.language;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

/** A managed Cargo binary with a bundled SDK dependency; user sources stay untouched. */
public final class RustLanguageProvider implements LanguageProvider {
    public String id() { return "rust"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.rs");
        File sdk = ProgramFiles.sdk(directory, "rust/Cargo.toml").getParentFile();
        ProgramFiles.sdk(directory, "rust/src/lib.rs");
        File project = new File(directory, ".tranforcpp/rust-project");
        Files.createDirectories(project.toPath());
        Gson gson = new Gson();
        String manifest = "[package]\nname=\"tranforcpp-program\"\nversion=\"0.0.0\"\nedition=\"2021\"\n[workspace]\n"
            + "[[bin]]\nname=\"tranforcpp_program\"\npath=" + gson.toJson(source.getAbsolutePath())
            + "\n[dependencies]\ntranforcpp={path=" + gson.toJson(sdk.getAbsolutePath()) + "}\n";
        File cargoManifest = new File(project, "Cargo.toml");
        Files.write(cargoManifest.toPath(), manifest.getBytes(StandardCharsets.UTF_8));
        ProgramFiles.build(directory, Arrays.asList(System.getProperty("tranforcpp.cargo", "cargo"), "build", "--release",
            "--manifest-path", cargoManifest.getAbsolutePath(), "--target-dir", new File(project, "target").getAbsolutePath(),
            "--bin", "tranforcpp_program", "--message-format=json"), 300);
        File executable = null;
        try (BufferedReader reader = Files.newBufferedReader(new File(directory, "compile.log").toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("{")) continue;
                JsonObject message = gson.fromJson(line, JsonObject.class);
                if (message.has("reason") && "compiler-artifact".equals(message.get("reason").getAsString())
                    && "tranforcpp_program".equals(message.getAsJsonObject("target").get("name").getAsString())
                    && message.has("executable") && !message.get("executable").isJsonNull())
                    executable = new File(message.get("executable").getAsString());
            }
        }
        if (executable == null || !executable.isFile()) throw new IOException("Cargo did not produce the program; see compile.log");
        logger.info("Rust compilation complete");
        return new LaunchCommand(directory, Collections.singletonList(executable.getAbsolutePath()));
    }
}

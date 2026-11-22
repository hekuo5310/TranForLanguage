package com.github.tranforlanguage.core.language;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

/**
 * A managed Cargo binary with a bundled SDK dependency; user sources stay untouched. Built for
 * {@code wasm32-wasip1} and translated to JVM bytecode when possible, otherwise as a native binary.
 */
public final class RustLanguageProvider implements LanguageProvider {
    public String id() { return "rust"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.rs");
        File sdk = ProgramFiles.sdk(directory, "rust/Cargo.toml").getParentFile();
        ProgramFiles.sdk(directory, "rust/src/lib.rs");
        File project = new File(directory, ".tranforlanguage/rust-project");
        Files.createDirectories(project.toPath());
        String manifest = "[package]\nname=\"tranforlanguage-program\"\nversion=\"0.0.0\"\nedition=\"2021\"\n[workspace]\n"
            + "[[bin]]\nname=\"tranforlanguage_program\"\npath=" + new Gson().toJson(source.getAbsolutePath())
            + "\n[dependencies]\ntranforlanguage={path=" + new Gson().toJson(sdk.getAbsolutePath()) + "}\n";
        File userManifest = new File(directory, "Cargo.toml");
        if (userManifest.isFile()) manifest = CargoManifest.managed(userManifest, sdk, source);
        File cargoManifest = new File(project, "Cargo.toml");
        Files.write(cargoManifest.toPath(), manifest.getBytes(StandardCharsets.UTF_8));
        String target = System.getProperty("tranforlanguage.wasm.rust.target", "wasm32-wasip1");
        LaunchCommand jvm = InJvm.attempt(configuration, logger, "Rust",
            () -> InJvm.wasm(directory, build(directory, project, cargoManifest, target), logger, false));
        if (jvm != null) return jvm;
        File executable = build(directory, project, cargoManifest, null);
        logger.info("Rust compilation complete");
        return new LaunchCommand(directory, Collections.singletonList(executable.getAbsolutePath()));
    }

    /** Builds the managed binary for {@code target} (null: the host) and returns the artifact cargo reports. */
    private static File build(File directory, File project, File cargoManifest, String target) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList(System.getProperty("tranforlanguage.cargo", "cargo"), "build", "--release",
            "--manifest-path", cargoManifest.getAbsolutePath(), "--target-dir", new File(project, "target").getAbsolutePath(),
            "--bin", "tranforlanguage_program", "--message-format=json"));
        if (target != null) command.addAll(Arrays.asList("--target", target));
        ProgramFiles.build(directory, command, 300);
        File artifact = null;
        Gson gson = new Gson();
        try (BufferedReader reader = Files.newBufferedReader(new File(directory, "compile.log").toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("{")) continue;
                JsonObject message = gson.fromJson(line, JsonObject.class);
                if (message.has("reason") && "compiler-artifact".equals(message.get("reason").getAsString())
                    && "tranforlanguage_program".equals(message.getAsJsonObject("target").get("name").getAsString())
                    && message.has("executable") && !message.get("executable").isJsonNull())
                    artifact = new File(message.get("executable").getAsString());
            }
        }
        if (artifact == null || !artifact.isFile()) throw new IOException("Cargo did not produce the program; see compile.log");
        return artifact;
    }
}

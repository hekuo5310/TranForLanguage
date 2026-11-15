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
            String mod = "module tranforlanguage-program\\n\\ngo 1.21\\n";
            Files.write(new File(src, "go.mod").toPath(), mod.getBytes(StandardCharsets.UTF_8));
        }
        String go = System.getProperty("tranforlanguage.go", "go");
        // Amend only the managed copy; never modify the plugin author's go.mod.
        ProgramFiles.build(directory, src, Arrays.asList(go, "mod", "edit",
            "-require=tranforlanguage@v0.0.0",
            "-replace=tranforlanguage=" + sdk.getAbsolutePath()), Collections.emptyMap(), 30);
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        File executable = new File(project, windows ? "tranforlanguage_program.exe" : "tranforlanguage_program");
        Files.deleteIfExists(executable.toPath());
        Map<String, String> environment = new HashMap<>();
        environment.put("GOWORK", "off"); environment.put("GOFLAGS", "-mod=mod"); environment.put("CGO_ENABLED", "0");
        ProgramFiles.build(directory, src, Arrays.asList(go, "build",
            "-o", executable.getAbsolutePath(), "."), environment, 300);
        if (!executable.isFile()) throw new IOException("Go did not produce the program; see compile.log");
        logger.info("Go compilation complete: " + sources.length + " source(s)");
        return new LaunchCommand(directory, Collections.singletonList(executable.getAbsolutePath()));
    }
}

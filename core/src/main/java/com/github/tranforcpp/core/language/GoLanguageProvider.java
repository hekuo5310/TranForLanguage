package com.github.tranforcpp.core.language;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Go {@code package main} programs. Top-level .go files are copied into a managed module that
 * replaces {@code tranforcpp} with the bundled SDK; user files and go.mod are never written.
 */
public final class GoLanguageProvider implements LanguageProvider {
    public String id() { return "go"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File[] sources = ProgramFiles.sources(directory, ".go");
        if (sources.length == 0) throw new IOException("Missing Go sources (*.go) in program directory");
        File sdk = ProgramFiles.sdk(directory, "go/go.mod").getParentFile();
        ProgramFiles.sdk(directory, "go/tranforcpp.go");
        File project = new File(directory, ".tranforcpp/go-project");
        ProgramFiles.deleteTree(new File(project, "src"));
        File src = new File(project, "src"); Files.createDirectories(src.toPath());
        for (File source : sources) Files.copy(source.toPath(), new File(src, source.getName()).toPath());
        String mod = "module tranforcpp-program\n\ngo 1.21\n\nrequire tranforcpp v0.0.0\n\nreplace tranforcpp => "
            + new Gson().toJson(sdk.getAbsolutePath()) + "\n";
        Files.write(new File(src, "go.mod").toPath(), mod.getBytes(StandardCharsets.UTF_8));
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        File executable = new File(project, windows ? "tranforcpp_program.exe" : "tranforcpp_program");
        Files.deleteIfExists(executable.toPath());
        Map<String, String> environment = new HashMap<>();
        environment.put("GOWORK", "off"); environment.put("GOFLAGS", "-mod=mod"); environment.put("CGO_ENABLED", "0");
        ProgramFiles.build(directory, src, Arrays.asList(System.getProperty("tranforcpp.go", "go"), "build",
            "-o", executable.getAbsolutePath(), "."), environment, 300);
        if (!executable.isFile()) throw new IOException("Go did not produce the program; see compile.log");
        logger.info("Go compilation complete: " + sources.length + " source(s)");
        return new LaunchCommand(directory, Collections.singletonList(executable.getAbsolutePath()));
    }
}

package com.github.tranforlanguage.core.language;

import com.google.gson.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

/** Java sources compiled with the bundled {@code tranforlanguage.TranForLanguage} SDK; runs in its own JVM. */
public final class JavaLanguageProvider implements LanguageProvider {
    public String id() { return "java"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File[] sources = ProgramFiles.sources(directory, ".java");
        if (sources.length == 0) throw new IOException("Missing Java sources (*.java) in program directory");
        String mainClass = "Main";
        if (configuration.has("mainClass")) {
            JsonElement value = configuration.get("mainClass");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("mainClass must be a string");
            mainClass = value.getAsString();
        }
        File sdk = ProgramFiles.sdk(directory, "java/tranforlanguage/TranForLanguage.java");
        File classes = new File(directory, ".tranforlanguage/java-classes");
        ProgramFiles.deleteTree(classes); Files.createDirectories(classes.toPath());
        List<String> args = new ArrayList<>(Arrays.asList(ProgramFiles.jdkTool("tranforlanguage.javac", "javac"),
            "-encoding", "UTF-8", "-d", classes.getAbsolutePath(), sdk.getAbsolutePath()));
        for (File source : sources) args.add(source.getAbsolutePath());
        ProgramFiles.build(directory, args, 180);
        logger.info("Java compilation complete: " + sources.length + " source(s)");
        return new LaunchCommand(directory, Arrays.asList(ProgramFiles.jdkTool("tranforlanguage.java", "java"),
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Xss2m",
            "-cp", classes.getAbsolutePath(), mainClass));
    }
}

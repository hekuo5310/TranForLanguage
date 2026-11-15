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
        // Ordinary local JARs and Maven pom.xml dependencies share the same classpath.
        File dependencies = new File(directory, ".tranforlanguage/java-deps");
        File pom = new File(directory, "pom.xml");
        if (pom.isFile()) {
            File stamp = new File(directory, ".tranforlanguage/java-dependencies");
            String fingerprint = ProgramFiles.fingerprint(pom);
            if (!ProgramFiles.matches(stamp, fingerprint) || !dependencies.isDirectory()) {
                // A failed replacement must never leave the previous success stamp usable.
                Files.deleteIfExists(stamp.toPath());
                ProgramFiles.deleteTree(dependencies);
                Files.createDirectories(dependencies.toPath());
                ProgramFiles.build(directory, Arrays.asList(
                    System.getProperty("tranforlanguage.maven", "mvn"), "-B", "-ntp",
                    "dependency:copy-dependencies", "-DincludeScope=runtime",
                    "-DoutputDirectory=" + dependencies.getAbsolutePath()), 300);
                ProgramFiles.stamp(stamp, fingerprint);
            }
        }
        List<File> jars = new ArrayList<>();
        List<File> folders = new ArrayList<>();
        folders.add(new File(directory, "lib"));
        if (pom.isFile()) folders.add(dependencies);
        for (File folder : folders) {
            File[] contents = folder.listFiles((d, name) -> name.endsWith(".jar"));
            if (contents != null) jars.addAll(Arrays.asList(contents));
        }
        jars.sort(Comparator.comparing(File::getName));
        StringBuilder classpath = new StringBuilder(classes.getAbsolutePath());
        for (File jar : jars) classpath.append(File.pathSeparator).append(jar.getAbsolutePath());
        List<String> args = new ArrayList<>(Arrays.asList(ProgramFiles.jdkTool("tranforlanguage.javac", "javac"),
            "-encoding", "UTF-8", "-cp", classpath.toString(), "-d", classes.getAbsolutePath(), sdk.getAbsolutePath()));
        for (File source : sources) args.add(source.getAbsolutePath());
        ProgramFiles.build(directory, args, 180);
        logger.info("Java compilation complete: " + sources.length + " source(s)");
        return new LaunchCommand(directory, Arrays.asList(ProgramFiles.jdkTool("tranforlanguage.java", "java"),
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Xss2m",
            "-cp", classpath.toString(), mainClass));
    }
}

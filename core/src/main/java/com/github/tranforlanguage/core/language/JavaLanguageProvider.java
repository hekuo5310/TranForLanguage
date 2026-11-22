package com.github.tranforlanguage.core.language;

import com.github.tranforlanguage.core.jvm.JavaInstrumenter;
import com.github.tranforlanguage.core.jvm.JavaProgram;
import com.github.tranforlanguage.core.jvm.JvmEngines;
import com.github.tranforlanguage.core.jvm.RuntimeLibraries;
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
        File bridge = ProgramFiles.sdk(directory, "java/tranforlanguage/ProgramSystem.java");
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
            "-encoding", "UTF-8", "-cp", classpath.toString(), "-d", classes.getAbsolutePath(), sdk.getAbsolutePath(), bridge.getAbsolutePath()));
        for (File source : sources) args.add(source.getAbsolutePath());
        ProgramFiles.build(directory, args, 180);
        logger.info("Java compilation complete: " + sources.length + " source(s)");
        final String main = mainClass;
        LaunchCommand jvm = InJvm.attempt(configuration, logger, "Java", () -> inJvm(directory, classes, jars, main, logger));
        if (jvm != null) return jvm;
        return new LaunchCommand(directory, Arrays.asList(ProgramFiles.jdkTool("tranforlanguage.java", "java"),
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Xss2m",
            "-cp", classpath.toString(), mainClass));
    }

    /**
     * Rewrites the compiled classes and library jars once, so the program can share the server JVM:
     * its System.in/out/err, exit and getenv go to its own streams instead of the server's.
     */
    private static LaunchCommand inJvm(File directory, File classes, List<File> jars, String mainClass, Logger logger) throws Exception {
        JvmEngines.requireJava(11, "In-JVM Java programs");
        JavaInstrumenter instrumenter = JvmEngines.load(RuntimeLibraries.JAVA, "AsmJavaInstrumenter", JavaInstrumenter.class, logger);
        File root = new File(directory, ".tranforlanguage/java-jvm");
        File rewritten = new File(root, "classes");
        ProgramFiles.deleteTree(rewritten);
        instrumenter.instrument(classes, rewritten);
        List<File> classpath = new ArrayList<>();
        classpath.add(rewritten);
        Set<String> current = new HashSet<>(Collections.singleton("classes"));
        for (File jar : jars) {
            String name = "jar-" + jar.getName();
            File output = new File(root, name), stamp = new File(root, name + ".stamp");
            String fingerprint = ProgramFiles.fingerprint(jar);
            if (!ProgramFiles.matches(stamp, fingerprint) || !output.isDirectory()) {
                Files.deleteIfExists(stamp.toPath());
                ProgramFiles.deleteTree(output);
                instrumenter.instrument(jar, output);
                ProgramFiles.stamp(stamp, fingerprint);
            }
            classpath.add(output);
            current.add(name); current.add(name + ".stamp");
        }
        // Libraries that are no longer on the classpath leave no stale rewritten copies behind.
        File[] entries = root.listFiles();
        if (entries != null) for (File entry : entries) if (!current.contains(entry.getName())) ProgramFiles.deleteTree(entry);
        logger.info("Java bytecode rewritten for the server JVM: " + (1 + jars.size()) + " classpath entr" + (jars.isEmpty() ? "y" : "ies"));
        return LaunchCommand.inJvm(directory, new JavaProgram(classpath, mainClass), Collections.<String, String>emptyMap());
    }
}

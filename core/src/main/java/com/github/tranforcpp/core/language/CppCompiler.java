package com.github.tranforcpp.core.language;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Compiles the existing C++17, JSON-lines plugins independently of Bukkit. */
public class CppCompiler {
    private final File directory;
    private final Logger logger;
    public CppCompiler(File directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }
    public File getCppDirectory() { return directory; }
    public List<File> getPluginFiles() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".cpp"));
        if (files == null) return Collections.emptyList();
        Arrays.sort(files);
        return Arrays.asList(files);
    }
    public int countPlugins() { return getPluginFiles().size(); }
    public File compile(File cppDir) {
        try { return compileChecked(cppDir); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("C++ compilation interrupted", e);
        }
    }
    private File compileChecked(File dir) throws IOException, InterruptedException {
        Files.createDirectories(dir.toPath());
        File[] sources = dir.listFiles((d, name) -> name.endsWith(".cpp"));
        if (sources == null || sources.length == 0) return null;
        Arrays.sort(sources);
        String compiler = System.getProperty("tranforcpp.compiler", "g++");
        File executable = new File(dir, System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
            ? "tranforcpp_plugin.exe" : "tranforcpp_plugin");
        List<String> args = new ArrayList<>(Arrays.asList(compiler, "-std=c++17", "-pthread", "-o", executable.getAbsolutePath()));
        for (File source : sources) args.add(source.getAbsolutePath());
        File log = new File(dir, "compile.log");
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) throw new IOException("C++ compilation timed out (120s); see " + log);
            if (process.exitValue() != 0 || !executable.isFile()) {
                throw new IOException("C++ compilation failed; see " + log);
            }
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        logger.info("C++ compilation complete: " + sources.length + " source(s)");
        return executable;
    }
}

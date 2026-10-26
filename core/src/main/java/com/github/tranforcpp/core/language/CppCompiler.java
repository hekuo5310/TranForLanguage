package com.github.tranforcpp.core.language;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

/** C11 / C++17 compilation with an injected SDK, independent of Bukkit. */
public class CppCompiler {
    private final File directory;
    private final Logger logger;
    private final boolean c;
    public CppCompiler(File directory, Logger logger) {
        this(directory, logger, false);
    }
    CppCompiler(File directory, Logger logger, boolean c) {
        this.directory = directory;
        this.logger = logger;
        this.c = c;
    }
    public File getCppDirectory() { return directory; }
    public List<File> getPluginFiles() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(c ? ".c" : ".cpp"));
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
            throw new IllegalStateException((c ? "C" : "C++") + " compilation interrupted", e);
        }
    }
    private File compileChecked(File dir) throws IOException, InterruptedException {
        Files.createDirectories(dir.toPath());
        File[] sources = dir.listFiles((d, name) -> name.endsWith(c ? ".c" : ".cpp"));
        if (sources == null || sources.length == 0) return null;
        Arrays.sort(sources);
        String compiler = System.getProperty(c ? "tranforcpp.c.compiler" : "tranforcpp.compiler", c ? "gcc" : "g++");
        File header = c ? ProgramFiles.sdk(dir, "c/tranforcpp.h")
            : ProgramFiles.resource(dir, "/tranforcpp_api.h", "cpp/tranforcpp_api.h");
        File executable = new File(dir, System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
            ? "tranforcpp_plugin.exe" : "tranforcpp_plugin");
        List<String> args = new ArrayList<>(Arrays.asList(compiler, c ? "-std=c11" : "-std=c++17", "-pthread",
            "-I", header.getParent(), "-include", header.getAbsolutePath(), "-o", executable.getAbsolutePath()));
        for (File source : sources) args.add(source.getAbsolutePath());
        if (c) args.add(ProgramFiles.sdk(dir, "c/runtime.c").getAbsolutePath());
        ProgramFiles.build(dir, args, 120);
        if (!executable.isFile()) throw new IOException("Compiler did not produce the program; see compile.log");
        logger.info((c ? "C" : "C++") + " compilation complete: " + sources.length + " source(s)");
        return executable;
    }
}

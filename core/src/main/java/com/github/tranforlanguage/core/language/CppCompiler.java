package com.github.tranforlanguage.core.language;

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
        String compiler = System.getProperty(c ? "tranforlanguage.c.compiler" : "tranforlanguage.cpp.compiler", c ? "gcc" : "g++");
        File header = c ? ProgramFiles.sdk(dir, "c/tranforlanguage.h")
            : ProgramFiles.sdk(dir, "cpp/tranforlanguage.hpp");
        File rpcHeader = ProgramFiles.sdk(dir, "c/tranforlanguage_rpc.h");
        File rpc = ProgramFiles.sdk(dir, "c/rpc.c");
        File executable = new File(dir, System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
            ? "tranforlanguage_program.exe" : "tranforlanguage_program");
        List<String> args = new ArrayList<>(Arrays.asList(compiler, c ? "-std=c11" : "-std=c++17", "-pthread",
            "-I", header.getParent(), "-I", rpcHeader.getParent(), "-include", header.getAbsolutePath(), "-o", executable.getAbsolutePath()));
        for (File source : sources) args.add(source.getAbsolutePath());
        // The shared event loop / call runtime; compiled as C++ by the C++ toolchain.
        if (!c) args.addAll(Arrays.asList("-x", "c++"));
        args.add(rpc.getAbsolutePath());
        ProgramFiles.build(dir, args, 120);
        if (!executable.isFile()) throw new IOException("Compiler did not produce the program; see compile.log");
        logger.info((c ? "C" : "C++") + " compilation complete: " + sources.length + " source(s)");
        return executable;
    }
}

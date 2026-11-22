package com.github.tranforlanguage.core.language;

import java.io.*;
import com.google.gson.*;
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
        return compile(cppDir, new JsonObject());
    }
    public File compile(File cppDir, JsonObject configuration) {
        try { return compileChecked(cppDir, configuration); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException((c ? "C" : "C++") + " compilation interrupted", e);
        }
    }
    /**
     * C only: builds a single-threaded WASI module for in-JVM translation, or returns null without sources.
     * Uses clang ({@code tranforlanguage.wasm.c.compiler}) with a WASI sysroot ({@code tranforlanguage.wasi.sysroot}).
     */
    File compileWasi(File dir) throws IOException, InterruptedException {
        if (!c) throw new IllegalStateException("C++ programs are not translated to WebAssembly");
        File[] sources = dir.listFiles((d, name) -> name.endsWith(".c"));
        if (sources == null || sources.length == 0) return null;
        Arrays.sort(sources);
        File header = ProgramFiles.sdk(dir, "c/tranforlanguage.h");
        ProgramFiles.sdk(dir, "c/tranforlanguage_rpc.h");
        File rpc = ProgramFiles.sdk(dir, "c/rpc.c");
        File module = new File(dir, ".tranforlanguage/program.wasm");
        Files.createDirectories(module.getParentFile().toPath());
        Files.deleteIfExists(module.toPath());
        List<String> args = new ArrayList<>(Arrays.asList(System.getProperty("tranforlanguage.wasm.c.compiler", "clang"),
            "--target=" + System.getProperty("tranforlanguage.wasm.c.target", "wasm32-wasi")));
        String sysroot = System.getProperty("tranforlanguage.wasi.sysroot");
        if (sysroot != null && !sysroot.isEmpty()) args.add("--sysroot=" + sysroot);
        args.addAll(Arrays.asList("-O2", "-std=c11", "-I", header.getParent(), "-include", header.getAbsolutePath(),
            "-o", module.getAbsolutePath()));
        for (File source : sources) args.add(source.getAbsolutePath());
        args.add(rpc.getAbsolutePath());
        ProgramFiles.build(dir, args, 120);
        if (!module.isFile()) throw new IOException("clang did not produce a WebAssembly module; see compile.log");
        logger.info("C compiled to WebAssembly: " + sources.length + " source(s)");
        return module;
    }

    private File compileChecked(File dir, JsonObject configuration) throws IOException, InterruptedException {
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
        // Reset the C++-only language override before pkg-config flags.
        if (!c) args.addAll(Arrays.asList("-x", "none"));
        if (configuration.has("pkgConfig")) {
            JsonArray packages = configuration.getAsJsonArray("pkgConfig");
            if (packages == null || packages.size() == 0)
                throw new IllegalArgumentException("pkgConfig must be a nonempty string array");
            List<String> command = new ArrayList<>();
            command.add(System.getProperty("tranforlanguage.pkgconfig", "pkg-config"));
            command.add("--cflags");
            command.add("--libs");
            for (JsonElement element : packages) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("pkgConfig must contain package names");
                String name = element.getAsString();
                if (!name.matches("[A-Za-z0-9_.+\\-]+") || name.startsWith("-"))
                    throw new IllegalArgumentException("Invalid pkg-config package: " + name);
                command.add(name);
            }
            String flags = ProgramFiles.capture(dir, command, 10, 65536);
            args.addAll(ProgramFiles.splitArguments(flags));
        }
        ProgramFiles.build(dir, args, 120);
        if (!executable.isFile()) throw new IOException("Compiler did not produce the program; see compile.log");
        logger.info((c ? "C" : "C++") + " compilation complete: " + sources.length + " source(s)");
        return executable;
    }
}

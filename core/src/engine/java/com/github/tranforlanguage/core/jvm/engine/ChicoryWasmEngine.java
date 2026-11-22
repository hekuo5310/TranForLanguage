package com.github.tranforlanguage.core.jvm.engine;

import com.dylibso.chicory.compiler.Cache;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import com.github.tranforlanguage.core.jvm.BytePipe;
import com.github.tranforlanguage.core.jvm.JvmProgram;
import com.github.tranforlanguage.core.jvm.WasmEngine;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

/** WebAssembly to JVM bytecode with Chicory's compiler; the generated classes are kept on disk. */
public final class ChicoryWasmEngine implements WasmEngine {
    @Override public void translate(File module, File classes) throws Exception {
        WasmModule parsed = Parser.parse(module);
        DiskCache cache = new DiskCache(classes.toPath());
        MachineFactoryCompiler.builder(parsed).withCache(cache).compile();
        if (cache.used == null) throw new IOException("Chicory produced no classes for " + module);
        // Classes of earlier builds of this program are keyed by other digests; drop them.
        File[] stale = classes.listFiles((dir, name) -> name.endsWith(".classes") && !name.equals(cache.used.getFileName().toString()));
        if (stale != null) for (File file : stale) Files.deleteIfExists(file.toPath());
    }

    @Override public JvmProgram program(File module, File classes, File directory, boolean cooperativeInput) throws Exception {
        WasmModule parsed = Parser.parse(module);
        DiskCache cache = new DiskCache(classes.toPath());
        Function<Instance, Machine> machines = MachineFactoryCompiler.builder(parsed).withCache(cache).compile();
        String name = module.getName();
        return new JvmProgram() {
            @Override public int run(InputStream stdin, OutputStream stdout, OutputStream stderr, Map<String, String> environment) {
                WasiOptions.Builder options = WasiOptions.builder()
                    .withStdin(stdin, false).withStdout(stdout, false).withStderr(stderr, false)
                    .withArguments(Collections.singletonList(name))
                    .withDirectory("/", directory.toPath()).withDirectory(".", directory.toPath());
                for (Map.Entry<String, String> variable : environment.entrySet()) options.withEnvironment(variable.getKey(), variable.getValue());
                try (WasiPreview1 wasi = WasiPreview1.builder().withOptions(options.build()).build()) {
                    ImportValues.Builder imports = ImportValues.builder().addFunction(wasi.toHostFunctions());
                    if (cooperativeInput) imports.addFunction(stdinReady(stdin));
                    Instance.builder(parsed).withMachineFactory(machines).withImportValues(imports.build()).build();
                    return 0;
                } catch (WasiExitException exit) {
                    return exit.exitCode();
                }
            }
        };
    }

    /** {@code tranforlanguage.stdin_ready() -> i32}: 1 when a whole input line (or end of input) is buffered. */
    private static HostFunction stdinReady(InputStream stdin) {
        BytePipe.Source source = stdin instanceof BytePipe.Source ? (BytePipe.Source) stdin : null;
        return new HostFunction("tranforlanguage", "stdin_ready", FunctionType.returning(ValType.I32), (instance, args) -> {
            boolean ready;
            try { ready = source == null ? stdin.available() > 0 : source.lineReady(); }
            catch (IOException e) { ready = true; }
            return new long[] {ready ? 1 : 0};
        });
    }

    /** Stores each translated module under its digest, as Chicory names it. */
    private static final class DiskCache implements Cache {
        private final Path directory;
        /** The stored translation that was read or written, if any. */
        Path used;
        DiskCache(Path directory) { this.directory = directory; }
        private Path file(String key) { return directory.resolve(key.replaceAll("[^A-Za-z0-9._-]", "_") + ".classes"); }
        @Override public byte[] get(String key) throws IOException {
            Path file = file(key);
            if (!Files.isRegularFile(file)) return null;
            used = file;
            return Files.readAllBytes(file);
        }
        @Override public void putIfAbsent(String key, byte[] value) throws IOException {
            Path file = file(key);
            used = file;
            if (Files.isRegularFile(file)) return;
            Files.createDirectories(directory);
            Path partial = Files.createTempFile(directory, "translate", ".part");
            Files.write(partial, value);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

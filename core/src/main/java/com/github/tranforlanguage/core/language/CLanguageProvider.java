package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.Collections;
import java.util.logging.Logger;

/** C11 programs: translated to JVM bytecode through WebAssembly when possible, otherwise a native binary. */
public final class CLanguageProvider implements LanguageProvider {
    public String id() { return "c"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        CppCompiler compiler = new CppCompiler(directory, logger, true);
        // pkg-config libraries are native code that WebAssembly cannot link against.
        if (compiler.countPlugins() > 0 && !configuration.has("pkgConfig")) {
            LaunchCommand jvm = InJvm.attempt(configuration, logger, "C",
                () -> InJvm.wasm(directory, compiler.compileWasi(directory), logger, false));
            if (jvm != null) return jvm;
        }
        File executable = compiler.compile(directory, configuration);
        return executable == null ? null : new LaunchCommand(directory, Collections.singletonList(executable.getAbsolutePath()));
    }
}

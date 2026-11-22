package com.github.tranforlanguage.core.language;

import com.github.tranforlanguage.core.jvm.JvmEngines;
import com.github.tranforlanguage.core.jvm.RuntimeLibraries;
import com.github.tranforlanguage.core.jvm.WasmEngine;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.Collections;
import java.util.logging.Logger;

/**
 * Shared steps for programs translated to JVM classes ahead of time. The translation happens once, while
 * the program is prepared; starting the program only loads the stored classes.
 */
final class InJvm {
    interface Build { LaunchCommand build() throws Exception; }

    private InJvm() {}

    /**
     * Tries the in-JVM build unless {@code program.json} asks for a process. In {@code auto} mode any failure
     * (old Java, missing toolchain, unsupported library) falls back to a separate process; returns null then.
     */
    static LaunchCommand attempt(JsonObject configuration, Logger logger, String language, Build build) throws Exception {
        JvmEngines.Mode mode = JvmEngines.mode(configuration);
        if (mode == JvmEngines.Mode.PROCESS) return null;
        try {
            return build.build();
        } catch (Exception failure) {
            if (mode == JvmEngines.Mode.JVM) throw failure;
            String reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            logger.info(language + ": running as a separate process (in-JVM translation unavailable: " + reason + ")");
            return null;
        }
    }

    /** Translates a WASI module to JVM classes next to the program and returns an in-JVM launch. */
    static LaunchCommand wasm(File directory, File module, Logger logger, boolean cooperativeInput) throws Exception {
        JvmEngines.requireJava(11, "WebAssembly translation");
        WasmEngine engine = JvmEngines.load(RuntimeLibraries.WASM, "ChicoryWasmEngine", WasmEngine.class, logger);
        File classes = new File(directory, ".tranforlanguage/jvm-classes");
        engine.translate(module, classes);
        logger.info("WebAssembly translated to JVM bytecode: " + module.getName());
        return LaunchCommand.inJvm(directory, engine.program(module, classes, directory, cooperativeInput),
            Collections.<String, String>emptyMap());
    }
}

package com.github.tranforlanguage.core.jvm;

import java.io.File;

/** Translates WebAssembly (WASI) modules to JVM classes and runs them. Implemented next to Chicory. */
public interface WasmEngine {
    /** Translates {@code module} into JVM classes stored in {@code classes}; does nothing when they are current. */
    void translate(File module, File classes) throws Exception;

    /**
     * A program running the translated module. With {@code cooperativeInput} the module may import
     * {@code tranforlanguage.stdin_ready}, so a runtime with green threads (Go) can poll for input
     * instead of blocking every goroutine inside a read.
     */
    JvmProgram program(File module, File classes, File directory, boolean cooperativeInput) throws Exception;
}

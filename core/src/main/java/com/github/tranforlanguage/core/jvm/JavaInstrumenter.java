package com.github.tranforlanguage.core.jvm;

import java.io.File;

/**
 * Rewrites a Java program's class files once, before it runs inside the server JVM: process-wide calls
 * on {@code System} and {@code Runtime} are redirected to the program's own {@code tranforlanguage.ProgramSystem}.
 */
public interface JavaInstrumenter {
    /** Copies {@code input} (a class directory or a jar) to directory {@code output}, rewriting every class. */
    void instrument(File input, File output) throws Exception;
}

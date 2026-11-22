package com.github.tranforlanguage.core.jvm;

import java.io.File;
import java.util.List;

/** Compiles Ruby to JVM classes with JRuby and runs them. Implemented next to JRuby. */
public interface RubyEngine {
    /** Compiles each source under {@code root} to a class file at the same relative path under {@code output}. */
    void compile(File root, List<File> sources, File output) throws Exception;

    /**
     * A program that loads the compiled {@code main} class. {@code require}/{@code require_relative}
     * of a compiled file load its class instead of parsing source again.
     */
    JvmProgram program(File output, File main, File directory) throws Exception;
}

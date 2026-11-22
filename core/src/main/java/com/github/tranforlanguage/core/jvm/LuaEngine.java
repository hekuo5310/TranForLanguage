package com.github.tranforlanguage.core.jvm;

import java.io.File;
import java.util.Map;

/** Compiles Lua to JVM classes with LuaJ's luajc and runs them. Implemented next to LuaJ. */
public interface LuaEngine {
    /**
     * Compiles each module (name such as {@code lib.helper} to its source file) into class files under
     * {@code output}, together with an index that maps module names to their classes.
     */
    void compile(Map<String, File> modules, File output) throws Exception;

    /**
     * A program running module {@code main}. {@code require} of a compiled module loads its class instead
     * of parsing source; relative file names resolve against {@code directory}, as in a separate process.
     */
    JvmProgram program(File output, String main, File directory) throws Exception;
}

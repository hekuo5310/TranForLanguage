package com.github.tranforcpp.core.language;


import com.google.gson.JsonObject;
import java.io.File;
import java.util.Collections;
import java.util.logging.Logger;

/** Legacy C++17 toolchain; the only layer that knows how C++ is built. */
public final class CppLanguageProvider implements LanguageProvider {
    public String id() { return "cpp"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) {
        File executable = new CppCompiler(directory, logger).compile(directory);
        return executable == null ? null : new LaunchCommand(directory, Collections.singletonList(executable.getAbsolutePath()));
    }
}

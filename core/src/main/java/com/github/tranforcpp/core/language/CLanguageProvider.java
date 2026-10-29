package com.github.tranforcpp.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.Collections;
import java.util.logging.Logger;

public final class CLanguageProvider implements LanguageProvider {
    public String id() { return "c"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) {
        File executable = new CppCompiler(directory, logger, true).compile(directory);
        return executable == null ? null : new LaunchCommand(directory, Collections.singletonList(executable.getAbsolutePath()));
    }
}

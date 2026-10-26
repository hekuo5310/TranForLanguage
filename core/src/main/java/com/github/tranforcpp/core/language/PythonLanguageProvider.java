package com.github.tranforcpp.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.Arrays;
import java.util.logging.Logger;

public final class PythonLanguageProvider implements LanguageProvider {
    public String id() { return "python"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.py");
        ProgramFiles.sdk(directory, "python/tranforcpp.py");
        File bootstrap = ProgramFiles.sdk(directory, "python/bootstrap.py");
        return new LaunchCommand(directory, Arrays.asList(System.getProperty("tranforcpp.python", "python3"),
            "-X", "utf8", "-u", bootstrap.getAbsolutePath(), source.getAbsolutePath()));
    }
}

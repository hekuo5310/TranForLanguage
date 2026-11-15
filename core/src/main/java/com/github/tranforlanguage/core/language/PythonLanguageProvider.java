package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.Arrays;
import java.util.logging.Logger;

public final class PythonLanguageProvider implements LanguageProvider {
    public String id() { return "python"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.py");
        ProgramFiles.sdk(directory, "python/tranforlanguage.py");
        File bootstrap = ProgramFiles.sdk(directory, "python/bootstrap.py");
        return new LaunchCommand(directory, Arrays.asList(System.getProperty("tranforlanguage.python", "python3"),
            "-X", "utf8", "-u", bootstrap.getAbsolutePath(), source.getAbsolutePath()));
    }
}

package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

/** Python 3.8+; requirements.txt opts into an isolated, cached virtual environment. */
public final class PythonLanguageProvider implements LanguageProvider {
    public String id() { return "python"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.py");
        ProgramFiles.sdk(directory, "python/tranforlanguage.py");
        File bootstrap = ProgramFiles.sdk(directory, "python/bootstrap.py");
        String interpreter = System.getProperty("tranforlanguage.python", "python3");
        File requirements = new File(directory, "requirements.txt");
        if (requirements.isFile()) {
            File venv = new File(directory, ".tranforlanguage/python-venv");
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            File python = new File(venv, windows ? "Scripts/python.exe" : "bin/python");
            ProgramFiles.cached(venv, "python:" + interpreter, () -> {
                ProgramFiles.build(directory, Arrays.asList(interpreter, "-m", "venv", venv.getAbsolutePath()), 120);
                ProgramFiles.build(directory, Arrays.asList(python.getAbsolutePath(), "-m", "pip",
                    "install", "--disable-pip-version-check", "--no-input", "-r", requirements.getAbsolutePath()), 300);
            }, requirements);
            if (!Files.isRegularFile(python.toPath())) throw new java.io.IOException("Python venv missing interpreter: " + python);
            interpreter = python.getAbsolutePath();
            logger.info("Python dependencies ready (isolated virtual environment)");
        }
        return new LaunchCommand(directory, Arrays.asList(interpreter,
            "-X", "utf8", "-u", bootstrap.getAbsolutePath(), source.getAbsolutePath()));
    }
}

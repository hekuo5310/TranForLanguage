package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.logging.Logger;

/** Python programs use a private virtualenv when requirements.txt is present. */
public final class PythonLanguageProvider implements LanguageProvider {
    public String id() { return "python"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.py");
        ProgramFiles.sdk(directory, "python/tranforlanguage.py");
        File bootstrap = ProgramFiles.sdk(directory, "python/bootstrap.py");
        String python = System.getProperty("tranforlanguage.python", "python3");
        File requirements = new File(directory, "requirements.txt");
        if (requirements.isFile()) {
            File venv = new File(directory, ".tranforlanguage/python-venv");
            String fingerprint = ProgramFiles.fingerprint(requirements, python);
            File stamp = new File(venv, ".tfl-requirements");
            if (!ProgramFiles.matches(stamp, fingerprint)) {
                // Re-create the environment so removed dependencies cannot leak into the new version.
                File candidate = new File(directory, ".tranforlanguage/python-venv-next");
                ProgramFiles.deleteTree(candidate);
                try {
                    ProgramFiles.build(directory, Arrays.asList(python, "-m", "venv", candidate.getAbsolutePath()), 120);
                    String pip = interpreter(candidate);
                    ProgramFiles.build(directory, Arrays.asList(pip, "-m", "pip", "install",
                        "--disable-pip-version-check", "-r", requirements.getAbsolutePath()), 300);
                    ProgramFiles.deleteTree(venv);
                    Files.move(candidate.toPath(), venv.toPath());
                    ProgramFiles.stamp(stamp, fingerprint);
                } finally { ProgramFiles.deleteTree(candidate); }
                logger.info("Python dependencies installed for " + directory.getName());
            }
            python = interpreter(venv);
        }
        return new LaunchCommand(directory, Arrays.asList(python, "-X", "utf8", "-u",
            bootstrap.getAbsolutePath(), source.getAbsolutePath()));
    }
    private static String interpreter(File venv) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return new File(venv, windows ? "Scripts/python.exe" : "bin/python").getAbsolutePath();
    }
}

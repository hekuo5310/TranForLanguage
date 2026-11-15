package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** PHP CLI scripts; Composer is optional and uses each program's vendor directory. */
public final class PhpLanguageProvider implements LanguageProvider {
    public String id() { return "php"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.php");
        File sdk = ProgramFiles.sdk(directory, "php/tranforlanguage.php");
        File manifest = new File(directory, "composer.json");
        if (manifest.isFile()) {
            File lock = new File(directory, "composer.lock");
            String fingerprint = ProgramFiles.fingerprint(manifest, lock.isFile() ? lock : null);
            File stamp = new File(directory, ".tranforlanguage/php-dependencies");
            if (!ProgramFiles.matches(stamp, fingerprint)) {
                ProgramFiles.build(directory, directory, Arrays.asList(
                    System.getProperty("tranforlanguage.composer", "composer"), "install",
                    "--no-interaction", "--no-progress", "--no-dev"), Collections.emptyMap(), 300);
                ProgramFiles.stamp(stamp, fingerprint);
                logger.info("PHP dependencies installed for " + directory.getName());
            }
        }
        return new LaunchCommand(directory, Arrays.asList(System.getProperty("tranforlanguage.php", "php"),
            "-d", "display_errors=stderr", "-d", "output_buffering=0", "-d", "auto_prepend_file=" + sdk.getAbsolutePath(),
            source.getAbsolutePath()));
    }
}

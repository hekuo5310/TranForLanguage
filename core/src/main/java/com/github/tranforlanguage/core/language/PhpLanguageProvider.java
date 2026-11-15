package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Logger;

/** PHP CLI; composer.json opts into private vendor dependencies and Composer autoload. */
public final class PhpLanguageProvider implements LanguageProvider {
    public String id() { return "php"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.php");
        File sdk = ProgramFiles.sdk(directory, "php/tranforlanguage.php");
        File manifest = new File(directory, "composer.json");
        if (manifest.isFile()) {
            File lock = new File(directory, "composer.lock");
            File project = new File(directory, ".tranforlanguage/php-project");
            ProgramFiles.cached(project, "composer", () -> {
                Files.copy(manifest.toPath(), new File(project, "composer.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (lock.isFile()) Files.copy(lock.toPath(), new File(project, "composer.lock").toPath(), StandardCopyOption.REPLACE_EXISTING);
                ProgramFiles.build(directory, project, Arrays.asList(
                    System.getProperty("tranforlanguage.composer", "composer"), "install",
                    "--no-dev", "--no-interaction", "--no-scripts", "--no-plugins", "--no-progress", "--prefer-dist"),
                    Collections.<String, String>emptyMap(), 300);
            }, manifest, lock);
            sdk = ProgramFiles.sdk(directory, "php/composer_bootstrap.php");
            logger.info("PHP dependencies ready");
        }
        return new LaunchCommand(directory, Arrays.asList(System.getProperty("tranforlanguage.php", "php"),
            "-d", "display_errors=stderr", "-d", "output_buffering=0", "-d", "auto_prepend_file=" + sdk.getAbsolutePath(),
            source.getAbsolutePath()));
    }
}

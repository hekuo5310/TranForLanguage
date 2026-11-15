package com.github.tranforcpp.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.Arrays;
import java.util.logging.Logger;

/** PHP CLI scripts; the bundled {@code Tranfor} class is preloaded, no require needed. */
public final class PhpLanguageProvider implements LanguageProvider {
    public String id() { return "php"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.php");
        File sdk = ProgramFiles.sdk(directory, "php/tranforcpp.php");
        return new LaunchCommand(directory, Arrays.asList(System.getProperty("tranforcpp.php", "php"),
            "-d", "display_errors=stderr", "-d", "output_buffering=0", "-d", "auto_prepend_file=" + sdk.getAbsolutePath(),
            source.getAbsolutePath()));
    }
}

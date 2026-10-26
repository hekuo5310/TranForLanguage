package com.github.tranforcpp.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.logging.Logger;

/** SPI for language-specific build steps. Game events and transport stay language neutral. */
public interface LanguageProvider {
    String id();
    /** Return null when no program is installed; never invoke a shell implicitly. */
    LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception;
}

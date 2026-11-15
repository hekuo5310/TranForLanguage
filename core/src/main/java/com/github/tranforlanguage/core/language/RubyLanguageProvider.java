package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.Arrays;
import java.util.logging.Logger;

/** Ruby scripts; {@code require 'tranforlanguage'} loads the bundled SDK. */
public final class RubyLanguageProvider implements LanguageProvider {
    public String id() { return "ruby"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.rb");
        File sdk = ProgramFiles.sdk(directory, "ruby/tranforlanguage.rb").getParentFile();
        return new LaunchCommand(directory, Arrays.asList(System.getProperty("tranforlanguage.ruby", "ruby"),
            "-E", "UTF-8:UTF-8", "-I", sdk.getAbsolutePath(), source.getAbsolutePath()));
    }
}

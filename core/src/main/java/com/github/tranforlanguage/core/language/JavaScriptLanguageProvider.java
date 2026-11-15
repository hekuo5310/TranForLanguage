package com.github.tranforlanguage.core.language;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** Node.js CommonJS programs; {@code require('tranforlanguage')} resolves to the bundled SDK. */
public final class JavaScriptLanguageProvider implements LanguageProvider {
    public String id() { return "javascript"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.js");
        File sdk = ProgramFiles.sdk(directory, "javascript/tranforlanguage.js").getParentFile();
        Map<String, String> environment = new HashMap<>();
        String inherited = System.getenv("NODE_PATH");
        environment.put("NODE_PATH", inherited == null || inherited.isEmpty() ? sdk.getAbsolutePath()
            : sdk.getAbsolutePath() + File.pathSeparator + inherited);
        return new LaunchCommand(directory, Arrays.asList(System.getProperty("tranforlanguage.node", "node"),
            source.getAbsolutePath()), environment);
    }
}

package com.github.tranforcpp.core.language;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

public final class LanguageProviders {
    private final Map<String, LanguageProvider> providers = new LinkedHashMap<>();
    public LanguageProviders() {
        register(new CppLanguageProvider());
        register(new ProcessLanguageProvider());
        for (LanguageProvider provider : ServiceLoader.load(LanguageProvider.class, LanguageProvider.class.getClassLoader())) register(provider);
    }
    public void register(LanguageProvider provider) {
        if (providers.containsKey(provider.id())) throw new IllegalArgumentException("Duplicate language: " + provider.id());
        providers.put(provider.id(), provider);
    }
    public LaunchCommand prepare(File directory, Logger logger) throws Exception {
        Files.createDirectories(directory.toPath());
        File manifest = new File(directory, "program.json");
        JsonObject config = new JsonObject();
        if (manifest.isFile()) try (Reader reader = new InputStreamReader(new FileInputStream(manifest), StandardCharsets.UTF_8)) {
            config = new Gson().fromJson(reader, JsonObject.class);
            if (config == null) throw new IllegalArgumentException("Empty program.json");
        }
        if (config.has("protocol") && config.get("protocol").getAsInt() != 1) throw new IllegalArgumentException("Unsupported program protocol version");
        String language = config.has("language") ? config.get("language").getAsString() : "cpp";
        LanguageProvider provider = providers.get(language);
        if (provider == null) throw new IllegalArgumentException("Unknown language provider: " + language);
        return provider.prepare(directory, config, logger);
    }
}

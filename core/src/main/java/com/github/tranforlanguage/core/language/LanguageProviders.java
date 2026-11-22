package com.github.tranforlanguage.core.language;

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
        register(new CLanguageProvider());
        register(new PythonLanguageProvider());
        register(new RustLanguageProvider());
        register(new JavaScriptLanguageProvider());
        register(new GoLanguageProvider());
        register(new RubyLanguageProvider());
        register(new PhpLanguageProvider());
        register(new JavaLanguageProvider());
        register(new LuaLanguageProvider());
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
        String language = config.has("language") ? config.get("language").getAsString() : detect(directory);
        LanguageProvider provider = providers.get(language);
        if (provider == null) throw new IllegalArgumentException("Unknown language provider: " + language);
        LaunchCommand command = provider.prepare(directory, config, logger);
        // "jvm" is a promise: a language or setup that only runs as a process must fail, not silently fall back.
        if (command != null && !command.inJvm() && com.github.tranforlanguage.core.jvm.JvmEngines.mode(config) == com.github.tranforlanguage.core.jvm.JvmEngines.Mode.JVM)
            throw new IllegalArgumentException("runtime \"jvm\" requested, but this " + language
                + " program can only run as a separate process (see the in-JVM requirements for the language)");
        return command;
    }
    private String detect(File directory) {
        Set<String> found = new LinkedHashSet<>();
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            String language = file.isFile() ? detectLanguage(file.getName()) : null;
            if (language != null) found.add(language);
        }
        if (found.size() > 1) throw new IllegalArgumentException("Multiple program languages found; select language in program.json: " + found);
        return found.isEmpty() ? "cpp" : found.iterator().next();
    }
    /** The built-in language a file selects when program.json names none, or null. */
    public static String detectLanguage(String name) {
        if (name.endsWith(".cpp")) return "cpp";
        if (name.endsWith(".c")) return "c";
        switch (name) {
            case "main.py": return "python";
            case "main.rs": return "rust";
            case "main.js": return "javascript";
            case "main.go": return "go";
            case "main.rb": return "ruby";
            case "main.php": return "php";
            case "Main.java": return "java";
            case "main.lua": return "lua";
            default: return null;
        }
    }
}

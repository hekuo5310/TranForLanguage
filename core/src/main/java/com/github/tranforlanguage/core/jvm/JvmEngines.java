package com.github.tranforlanguage.core.jvm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * Chooses between running a program inside the server JVM and as a separate process, and loads the
 * translator for a language on first use.
 *
 * <p>{@code program.json} may set {@code "runtime"} to {@code "jvm"} (in-JVM or fail), {@code "process"}
 * (always a separate process) or {@code "auto"} (the default: in-JVM when possible, otherwise a process).
 * The system property {@code tranforlanguage.runtime} sets the default for every program.
 */
public final class JvmEngines {
    public enum Mode { AUTO, JVM, PROCESS }
    private static final Map<List<RuntimeLibraries.Artifact>, EngineLoader> LOADERS = new HashMap<>();

    private JvmEngines() {}

    public static Mode mode(JsonObject configuration) {
        String value = System.getProperty("tranforlanguage.runtime", "auto");
        if (configuration.has("runtime")) {
            JsonElement runtime = configuration.get("runtime");
            if (!runtime.isJsonPrimitive() || !runtime.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("runtime must be \"auto\", \"jvm\" or \"process\"");
            value = runtime.getAsString();
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "auto": return Mode.AUTO;
            case "jvm": return Mode.JVM;
            case "process": return Mode.PROCESS;
            default: throw new IllegalArgumentException("runtime must be \"auto\", \"jvm\" or \"process\": " + value);
        }
    }

    /** The running JVM's feature version (8, 11, 17, 21, ...). */
    public static int javaVersion() {
        String spec = System.getProperty("java.specification.version", "1.8");
        if (spec.startsWith("1.")) spec = spec.substring(2);
        try { return Integer.parseInt(spec); } catch (NumberFormatException e) { return 8; }
    }

    /** Throws with a readable reason when this JVM is too old for a translator. */
    public static void requireJava(int version, String what) {
        if (javaVersion() < version)
            throw new UnsupportedOperationException(what + " needs Java " + version + "+, this server runs Java " + javaVersion());
    }

    /** Instantiates glue class {@code implementation} next to its libraries, as {@code api}. */
    public static synchronized <T> T load(List<RuntimeLibraries.Artifact> libraries, String implementation, Class<T> api, Logger logger) throws Exception {
        EngineLoader loader = LOADERS.get(libraries);
        if (loader == null) {
            List<File> jars = RuntimeLibraries.resolve(libraries, logger);
            loader = new EngineLoader(jars, JvmEngines.class.getClassLoader());
            LOADERS.put(libraries, loader);
        }
        Class<?> type = Class.forName(EngineLoader.ENGINE_PACKAGE + implementation, true, loader);
        return api.cast(type.getConstructor().newInstance());
    }
}

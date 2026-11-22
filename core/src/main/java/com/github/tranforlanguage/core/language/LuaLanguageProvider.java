package com.github.tranforlanguage.core.language;

import com.github.tranforlanguage.core.jvm.JvmEngines;
import com.github.tranforlanguage.core.jvm.LuaEngine;
import com.github.tranforlanguage.core.jvm.RuntimeLibraries;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

/**
 * Lua programs. Compiled to JVM classes with LuaJ (Lua 5.2) and run inside the server when possible,
 * otherwise run by a {@code lua} interpreter with the bundled SDK on {@code LUA_PATH}.
 */
public final class LuaLanguageProvider implements LanguageProvider {
    public String id() { return "lua"; }
    public LaunchCommand prepare(File directory, JsonObject configuration, Logger logger) throws Exception {
        File source = ProgramFiles.entrypoint(directory, configuration, "main.lua");
        File sdk = ProgramFiles.sdk(directory, "lua/tranforlanguage.lua").getParentFile();
        LaunchCommand jvm = InJvm.attempt(configuration, logger, "Lua", () -> inJvm(directory, sdk, source, logger));
        if (jvm != null) return jvm;
        Map<String, String> environment = new HashMap<>();
        String inherited = System.getenv("LUA_PATH");
        // ";;" keeps the interpreter's default search path after the SDK and the program directory.
        environment.put("LUA_PATH", sdk.getAbsolutePath() + File.separator + "?.lua;" + directory.getAbsolutePath() + File.separator + "?.lua;"
            + (inherited == null || inherited.isEmpty() ? ";" : inherited));
        return new LaunchCommand(directory, Arrays.asList(System.getProperty("tranforlanguage.lua", "lua"), source.getAbsolutePath()), environment);
    }

    /** Compiles the program's Lua files and the SDK with luajc, once per change. */
    private static LaunchCommand inJvm(File directory, File sdk, File source, Logger logger) throws Exception {
        JvmEngines.requireJava(11, "LuaJ");
        Map<String, File> modules = new TreeMap<>();
        collect(directory, directory, modules);
        modules.put("tranforlanguage", new File(sdk, "tranforlanguage.lua"));
        String main = module(directory, source);
        LuaEngine engine = JvmEngines.load(RuntimeLibraries.LUA, "LuaJEngine", LuaEngine.class, logger);
        File output = new File(directory, ".tranforlanguage/lua-classes");
        File stamp = new File(directory, ".tranforlanguage/lua-classes.stamp");
        List<Object> inputs = new ArrayList<>();
        inputs.add(RuntimeLibraries.LUA.toString());
        for (Map.Entry<String, File> module : modules.entrySet()) { inputs.add(module.getKey()); inputs.add(module.getValue()); }
        String fingerprint = ProgramFiles.fingerprint(inputs.toArray());
        if (!ProgramFiles.matches(stamp, fingerprint) || !output.isDirectory()) {
            Files.deleteIfExists(stamp.toPath());
            ProgramFiles.deleteTree(output);
            engine.compile(modules, output);
            ProgramFiles.stamp(stamp, fingerprint);
            logger.info("Lua compiled to JVM classes with LuaJ: " + (modules.size() - 1) + " file(s)");
        }
        return LaunchCommand.inJvm(directory, engine.program(output, main, directory), Collections.<String, String>emptyMap());
    }

    /** The module name require() uses for a file: {@code lib/util.lua} is {@code lib.util}, {@code x/init.lua} is {@code x}. */
    private static String module(File directory, File file) {
        String relative = directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        String name = relative.substring(0, relative.length() - ".lua".length());
        if (name.endsWith("/init")) name = name.substring(0, name.length() - "/init".length());
        return name.replace('/', '.');
    }

    /** Lua files of the program, skipping hidden folders such as the managed .tranforlanguage. */
    private static void collect(File directory, File folder, Map<String, File> modules) {
        File[] files = folder.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File file : files) {
            if (file.getName().startsWith(".")) continue;
            if (file.isDirectory()) collect(directory, file, modules);
            else if (file.getName().endsWith(".lua")) modules.put(module(directory, file), file);
        }
    }
}

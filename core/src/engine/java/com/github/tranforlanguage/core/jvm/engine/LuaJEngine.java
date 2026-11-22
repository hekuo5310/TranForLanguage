package com.github.tranforlanguage.core.jvm.engine;

import com.github.tranforlanguage.core.jvm.JvmProgram;
import com.github.tranforlanguage.core.jvm.LuaEngine;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.luaj.vm2.luajc.LuaJC;

/** Lua to JVM bytecode with LuaJ's luajc; the classes are written once and loaded on every start. */
public final class LuaJEngine implements LuaEngine {
    private static final String INDEX = "modules.properties";

    /** Thrown by {@code os.exit}; an Error so that Lua's pcall cannot swallow it. */
    static final class Exit extends Error {
        private static final long serialVersionUID = 1L;
        final int code;
        Exit(int code) { super("exit " + code, null, false, false); this.code = code; }
    }

    @Override public void compile(Map<String, File> modules, File output) throws Exception {
        Files.createDirectories(output.toPath());
        Properties index = new Properties();
        int number = 0;
        for (Map.Entry<String, File> module : modules.entrySet()) {
            // A plain identifier as chunk name gives predictable, collision-free class names.
            String chunk = "tranforlanguage_lua_" + number++;
            Hashtable<?, ?> classes;
            try (InputStream in = new FileInputStream(module.getValue())) {
                classes = LuaJC.instance.compileAll(in, chunk, module.getValue().getPath(), JsePlatform.standardGlobals(), false);
            }
            if (!classes.containsKey(chunk)) throw new IOException("luajc produced no class for " + module.getValue());
            for (Map.Entry<?, ?> type : classes.entrySet())
                Files.write(new File(output, type.getKey() + ".class").toPath(), (byte[]) type.getValue());
            index.setProperty(module.getKey(), chunk);
        }
        try (Writer writer = Files.newBufferedWriter(new File(output, INDEX).toPath(), StandardCharsets.UTF_8)) {
            index.store(writer, "Lua module -> compiled class");
        }
    }

    @Override public JvmProgram program(File output, String main, File directory) throws Exception {
        Properties index = new Properties();
        try (Reader reader = Files.newBufferedReader(new File(output, INDEX).toPath(), StandardCharsets.UTF_8)) { index.load(reader); }
        if (index.getProperty(main) == null) throw new IOException("Lua module " + main + " was not compiled");
        return new JvmProgram() {
            @Override public int run(InputStream stdin, OutputStream stdout, OutputStream stderr, Map<String, String> environment) throws Exception {
                Globals globals = JsePlatform.standardGlobals();
                globals.STDIN = stdin;
                globals.STDOUT = new PrintStream(stdout, false, "UTF-8");
                globals.STDERR = new PrintStream(stderr, true, "UTF-8");
                // Relative names resolve against the program directory, as they would in its own process.
                globals.finder = name -> {
                    try { return new FileInputStream(resolve(directory, name)); }
                    catch (FileNotFoundException missing) { return null; }
                };
                LuaTable io = (LuaTable) globals.get("io");
                io.set("open", relative(io.get("open"), directory));
                io.set("lines", relative(io.get("lines"), directory));
                LuaTable os = (LuaTable) globals.get("os");
                os.set("getenv", new OneArgFunction() {
                    @Override public LuaValue call(LuaValue name) {
                        String value = environment.get(name.checkjstring());
                        return value == null ? NIL : valueOf(value);
                    }
                });
                os.set("exit", new VarArgFunction() {
                    @Override public Varargs invoke(Varargs args) {
                        LuaValue code = args.arg1();
                        throw new Exit(code.isboolean() ? (code.toboolean() ? 0 : 1) : code.optint(0));
                    }
                });
                LuaTable packages = (LuaTable) globals.get("package");
                String base = directory.getAbsolutePath() + File.separator;
                packages.set("path", base + "?.lua;" + base + "?" + File.separator + "init.lua");
                ClassLoader classes = new CompiledClasses(output, LuaJEngine.class.getClassLoader());
                LuaTable preload = (LuaTable) packages.get("preload");
                LuaFunction entry = null;
                for (String module : index.stringPropertyNames()) {
                    LuaFunction chunk = (LuaFunction) classes.loadClass(index.getProperty(module)).getConstructor().newInstance();
                    chunk.initupvalue1(globals);
                    preload.set(module, chunk);
                    if (module.equals(main)) entry = chunk;
                }
                try {
                    entry.call();
                    return 0;
                } catch (Exception | Error failure) {
                    // LuaJ may wrap the exit in a LuaError when it crosses a coroutine.
                    for (Throwable t = failure; t != null; t = t.getCause()) if (t instanceof Exit) return ((Exit) t).code;
                    throw failure;
                } finally {
                    globals.STDOUT.flush();
                    globals.STDERR.flush();
                }
            }
        };
    }

    private static File resolve(File directory, String name) {
        File file = new File(name);
        return file.isAbsolute() ? file : new File(directory, name);
    }

    /** Wraps io.open / io.lines so a relative file name is taken from the program directory. */
    private static LuaValue relative(LuaValue original, File directory) {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (!args.arg1().isstring()) return original.invoke(args);
                LuaValue path = valueOf(resolve(directory, args.arg1().tojstring()).getPath());
                return original.invoke(varargsOf(path, args.subargs(2)));
            }
        };
    }

    /** Defines the luajc classes of one program from its output folder. */
    private static final class CompiledClasses extends ClassLoader {
        private final File folder;
        CompiledClasses(File folder, ClassLoader parent) { super(parent); this.folder = folder; }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            File file = new File(folder, name + ".class");
            if (!file.isFile()) throw new ClassNotFoundException(name);
            try {
                byte[] code = Files.readAllBytes(file.toPath());
                return defineClass(name, code, 0, code.length);
            } catch (IOException e) { throw new ClassNotFoundException(name, e); }
        }
    }
}

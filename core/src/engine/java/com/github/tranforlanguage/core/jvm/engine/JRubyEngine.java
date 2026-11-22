package com.github.tranforlanguage.core.jvm.engine;

import com.github.tranforlanguage.core.jvm.JvmProgram;
import com.github.tranforlanguage.core.jvm.RubyEngine;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JRuby through its embedding API, called reflectively: JRuby's classes need Java 21, newer than this
 * glue is compiled for, and they are only present once {@code jruby-complete} has been downloaded.
 */
public final class JRubyEngine implements RubyEngine {
    /** Makes require/require_relative of compiled files load the class files; everything else is untouched. */
    private static final String BOOTSTRAP = String.join("\n",
        "module TranForLanguageAot",
        "  ROOT = $tranforlanguage_root",
        "  def self.compiled(path, base)",
        "    target = File.expand_path(path.sub(/\\.rb\\z/, '') + '.class', base)",
        "    File.file?(target) ? target : nil",
        "  end",
        "  def self.load_once(target)",
        "    return false if $LOADED_FEATURES.include?(target)",
        "    $LOADED_FEATURES << target",
        "    load target",
        "    true",
        "  end",
        "end",
        "module Kernel",
        "  alias_method :tranforlanguage_require, :require",
        "  def require(path)",
        "    name = path.to_s",
        "    target = name.start_with?('/') ? nil : TranForLanguageAot.compiled(name, TranForLanguageAot::ROOT)",
        "    target ? TranForLanguageAot.load_once(target) : tranforlanguage_require(path)",
        "  end",
        "  def require_relative(path)",
        "    base = File.dirname(caller_locations(1, 1).first.absolute_path.to_s)",
        "    target = TranForLanguageAot.compiled(path.to_s, base)",
        "    target ? TranForLanguageAot.load_once(target) : tranforlanguage_require(File.expand_path(path.to_s, base))",
        "  end",
        "  private :require, :require_relative",
        "end",
        "begin",
        "  load $tranforlanguage_main",
        "  0",
        "rescue SystemExit => ended",
        "  ended.status",
        "end");

    private final ClassLoader loader = JRubyEngine.class.getClassLoader();

    @Override public void compile(File root, List<File> sources, File output) throws Exception {
        Object container = container();
        try {
            List<String> argv = new ArrayList<>();
            argv.add("-t"); argv.add(output.getAbsolutePath());
            argv.add("--dir"); argv.add(root.getAbsolutePath());
            for (File source : sources) argv.add(source.getAbsolutePath());
            call(container, "put", new Class<?>[] {String.class, Object.class}, "$tranforlanguage_argv", argv);
            Object status = run(container, "require 'jruby/compiler'; JRuby::Compiler.compile_argv($tranforlanguage_argv.to_a)");
            if (status instanceof Number && ((Number) status).intValue() != 0)
                throw new IllegalStateException("jrubyc failed with status " + status);
        } finally { terminate(container); }
    }

    @Override public JvmProgram program(File output, File main, File directory) {
        return new JvmProgram() {
            private volatile Object running;
            @Override public int run(InputStream stdin, OutputStream stdout, OutputStream stderr, Map<String, String> environment) throws Exception {
                Object container = container();
                running = container;
                try {
                    call(container, "setInput", new Class<?>[] {InputStream.class}, stdin);
                    call(container, "setOutput", new Class<?>[] {PrintStream.class}, new PrintStream(stdout, true, "UTF-8"));
                    call(container, "setError", new Class<?>[] {PrintStream.class}, new PrintStream(stderr, true, "UTF-8"));
                    call(container, "setEnvironment", new Class<?>[] {Map.class}, environment);
                    call(container, "setCurrentDirectory", new Class<?>[] {String.class}, directory.getAbsolutePath());
                    call(container, "put", new Class<?>[] {String.class, Object.class}, "$tranforlanguage_root", output.getAbsolutePath());
                    call(container, "put", new Class<?>[] {String.class, Object.class}, "$tranforlanguage_main", main.getAbsolutePath());
                    Object status = JRubyEngine.this.run(container, BOOTSTRAP);
                    return status instanceof Number ? ((Number) status).intValue() : 0;
                } finally {
                    running = null;
                    terminate(container);
                }
            }
            @Override public void cancel() {
                Object container = running;
                if (container != null) terminate(container);
            }
        };
    }

    private Object container() throws Exception {
        Class<?> scope = Class.forName("org.jruby.embed.LocalContextScope", true, loader);
        Class<?> variables = Class.forName("org.jruby.embed.LocalVariableBehavior", true, loader);
        Class<?> type = Class.forName("org.jruby.embed.ScriptingContainer", true, loader);
        return type.getConstructor(scope, variables).newInstance(constant(scope, "SINGLETHREAD"), constant(variables, "TRANSIENT"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(Class<?> type, String name) { return Enum.valueOf((Class) type, name); }

    private Object run(Object container, String script) throws Exception {
        return call(container, "runScriptlet", new Class<?>[] {String.class}, script);
    }

    private static void terminate(Object container) {
        try { call(container, "terminate", new Class<?>[0]); } catch (Exception ignored) { /* already gone */ }
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}

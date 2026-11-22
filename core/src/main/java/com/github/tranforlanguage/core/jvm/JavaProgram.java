package com.github.tranforlanguage.core.jvm;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * Runs a Java program, whose classes were rewritten by a {@link JavaInstrumenter}, in its own class loader
 * inside the server JVM. The loader sees only the JDK, never the server or plugin classes, and
 * {@code tranforlanguage.ProgramSystem} gives the program its own stdin, stdout, stderr, environment and exit.
 */
public final class JavaProgram implements JvmProgram {
    private static final String BRIDGE = "tranforlanguage.ProgramSystem";
    private static final String EXIT = BRIDGE + "$Exit";
    private final List<File> classpath;
    private final String mainClass;
    private volatile Integer requestedExit;
    private volatile Thread mainThread;

    public JavaProgram(List<File> classpath, String mainClass) {
        this.classpath = new ArrayList<>(classpath);
        this.mainClass = mainClass;
    }

    @Override public int run(InputStream stdin, OutputStream stdout, OutputStream stderr, Map<String, String> environment) throws Exception {
        URL[] urls = new URL[classpath.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = classpath.get(i).toURI().toURL();
        // The platform (Java 9+) or extension (Java 8) loader: JDK classes only.
        ClassLoader jdk = ClassLoader.getSystemClassLoader().getParent();
        mainThread = Thread.currentThread();
        try (URLClassLoader loader = new URLClassLoader(urls, jdk)) {
            Class<?> bridge = Class.forName(BRIDGE, true, loader);
            bridge.getField("in").set(null, stdin);
            bridge.getField("out").set(null, new PrintStream(stdout, true, "UTF-8"));
            bridge.getField("err").set(null, new PrintStream(stderr, true, "UTF-8"));
            bridge.getField("environment").set(null, Collections.unmodifiableMap(new LinkedHashMap<>(environment)));
            Thread.currentThread().setContextClassLoader(loader);
            Method main = Class.forName(mainClass, true, loader).getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException thrown) {
                Integer code = exitCode(thrown.getCause());
                if (code != null) return code;
                if (requestedExit != null) return requestedExit;
                throw rethrow(thrown.getCause());
            }
            // Like a JVM, the program lives until its last non-daemon thread ends or it exits.
            awaitThreads();
            return requestedExit == null ? 0 : requestedExit;
        }
    }

    @Override public boolean uncaught(Thread thread, Throwable failure) {
        Integer code = exitCode(failure);
        if (code == null) return false;
        // System.exit from another thread ends the whole program.
        requestedExit = code;
        Thread main = mainThread;
        if (main != null) main.getThreadGroup().interrupt();
        return true;
    }

    private void awaitThreads() throws InterruptedException {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        while (requestedExit == null) {
            Thread[] threads = new Thread[group.activeCount() + 8];
            int count = group.enumerate(threads, true);
            Thread waiting = null;
            for (int i = 0; i < count; i++)
                if (threads[i] != Thread.currentThread() && !threads[i].isDaemon() && threads[i].isAlive()) { waiting = threads[i]; break; }
            if (waiting == null) return;
            try { waiting.join(); }
            catch (InterruptedException stop) { if (requestedExit == null) throw stop; }
        }
    }

    private static Integer exitCode(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getClass().getName().equals(EXIT)) {
                try { return t.getClass().getField("code").getInt(t); }
                catch (ReflectiveOperationException e) { return 1; }
            }
        }
        return null;
    }

    private static Exception rethrow(Throwable failure) {
        if (failure instanceof Exception) return (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        return new RuntimeException(failure);
    }
}

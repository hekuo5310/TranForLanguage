package com.github.tranforlanguage.core.jvm;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * Loads translator libraries and the glue code that drives them, isolated from the server. Library
 * classes are looked up here first, so an older ASM on a mod loader's classpath never wins. The glue
 * classes ship inside this jar but are defined in this loader, next to the libraries they call; they
 * reach the rest of the core (for example {@link JvmProgram}) through the parent.
 */
final class EngineLoader extends URLClassLoader {
    static final String ENGINE_PACKAGE = "com.github.tranforlanguage.core.jvm.engine.";
    static { registerAsParallelCapable(); }

    EngineLoader(List<File> jars, ClassLoader parent) throws IOException {
        super(urls(jars), parent);
    }

    private static URL[] urls(List<File> jars) throws IOException {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toURI().toURL();
        return urls;
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                if (name.startsWith(ENGINE_PACKAGE)) type = defineEngineClass(name);
                else if (!sharedWithParent(name)) {
                    try { type = findClass(name); } catch (ClassNotFoundException notHere) { type = null; }
                }
                if (type == null) type = getParent().loadClass(name);
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }

    /** JDK classes and the core's own API must be the same classes on both sides. */
    private static boolean sharedWithParent(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")
            || (name.startsWith("com.github.tranforlanguage.") && !name.startsWith(ENGINE_PACKAGE));
    }

    private Class<?> defineEngineClass(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream in = getParent().getResourceAsStream(resource)) {
            if (in == null) throw new ClassNotFoundException(name);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            byte[] code = bytes.toByteArray();
            return defineClass(name, code, 0, code.length);
        } catch (IOException e) { throw new ClassNotFoundException(name, e); }
    }

    @Override public URL getResource(String name) {
        // Libraries find their own resources (for example JRuby's bundled standard library) here first.
        URL local = findResource(name);
        return local != null ? local : super.getResource(name);
    }
}

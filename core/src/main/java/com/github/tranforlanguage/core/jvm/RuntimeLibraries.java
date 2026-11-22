package com.github.tranforlanguage.core.jvm;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.logging.Logger;

/**
 * Translator and runtime libraries for in-JVM programs, downloaded from Maven Central the first time a
 * language needs them. Every file is pinned to a SHA-256 digest, so a changed or corrupted download is
 * rejected instead of loaded. Nothing is bundled into the plugin or mod jar.
 */
public final class RuntimeLibraries {
    /** One pinned Maven artifact. */
    public static final class Artifact {
        final String group, name, version, sha256;
        Artifact(String coordinates, String sha256) {
            String[] parts = coordinates.split(":");
            group = parts[0]; name = parts[1]; version = parts[2]; this.sha256 = sha256;
        }
        String path() { return group.replace('.', '/') + "/" + name + "/" + version + "/" + name + "-" + version + ".jar"; }
        @Override public String toString() { return group + ":" + name + ":" + version; }
    }

    private static final List<Artifact> ASM = Arrays.asList(
        new Artifact("org.ow2.asm:asm:9.9.1", "6f3828a215c920059a5efa2fb55c233d6c54ec5cadca99ce1b1bdd10077c7ddd"),
        new Artifact("org.ow2.asm:asm-tree:9.9.1", "0f3555096b720b820bbacab0b515589bee0200bee099bda14c561738ae837ba1"),
        new Artifact("org.ow2.asm:asm-analysis:9.9.1", "6260bffc8ec008dd1b713702c7994e2c94d188a3da5bef9e87278a16df6a7522"),
        new Artifact("org.ow2.asm:asm-commons:9.9.1", "c2319e014ce7199f2b7f7d56d6bb991863168c3f4b6cd6c9f542a4937ef7ef88"),
        new Artifact("org.ow2.asm:asm-util:9.9.1", "c5ebbbeaf68126af094b42fa4800f59bc4413abd02d95b9aefad722cd257e207"));
    /** Chicory: WebAssembly translated to JVM bytecode. */
    public static final List<Artifact> WASM = concat(ASM, Arrays.asList(
        new Artifact("com.dylibso.chicory:wasm:1.7.5", "16558a66bac04f7e00ea06950f52d87ce2cfff535a54bac2115f2c13e0b3050b"),
        new Artifact("com.dylibso.chicory:runtime:1.7.5", "cdbb2bd4e353eacff78da5dd3454694316eaa6d2950aab3d1517dca41ae4a0b2"),
        new Artifact("com.dylibso.chicory:log:1.7.5", "79e4e505060d48dee4f520daa9591dbb9b9dc2e3073e1a5fcb2018bfd2d5d1f2"),
        new Artifact("com.dylibso.chicory:wasi:1.7.5", "52f68c155b1dc4ff6df9a6b9eb84201029886561e87bc77a858bd14333c1cfbc"),
        new Artifact("com.dylibso.chicory:compiler:1.7.5", "1a54cc0b3a79aa914f7f2fa5e7b7ed902702f3ce7aa155ec0cfc74b386a97377")));
    /** ASM, used to redirect a Java program's process-level calls to its own streams. */
    public static final List<Artifact> JAVA = ASM;
    /** JRuby: Ruby compiled to JVM classes. */
    public static final List<Artifact> RUBY = Collections.singletonList(
        new Artifact("org.jruby:jruby-complete:10.1.3.0", "f38876a273f9287f01cc7680acf2f1bf9592c97f29d5ff4be7f2a1eba271c514"));

    /** LuaJ: Lua compiled to JVM classes by luajc, which generates bytecode with BCEL. */
    public static final List<Artifact> LUA = Collections.unmodifiableList(Arrays.asList(
        new Artifact("org.luaj:luaj-jse:3.0.1", "9b1f0a3e8f68427c6d74c2bf00ae0e6dbfce35994d3001fed4cef6ecda50be55"),
        new Artifact("org.apache.bcel:bcel:5.2", "7b87e2fd9ac3205a6e5ba9ef5e58a8f0ab8d1a0e0d00cb2a761951fa298cc733")));

    private static final List<String> DEFAULT_REPOSITORIES = Arrays.asList(
        "https://repo1.maven.org/maven2/", "https://maven-central.storage-download.googleapis.com/maven2/");
    private static volatile File store;

    private RuntimeLibraries() {}

    private static List<Artifact> concat(List<Artifact> a, List<Artifact> b) {
        List<Artifact> all = new ArrayList<>(a); all.addAll(b); return Collections.unmodifiableList(all);
    }

    /** Where downloaded libraries live; the program manager points this at its own directory. */
    public static void store(File directory) { store = directory; }

    static File storeDirectory() {
        String configured = System.getProperty("tranforlanguage.runtimes");
        if (configured != null && !configured.isEmpty()) return new File(configured);
        File chosen = store;
        return chosen != null ? chosen : new File(System.getProperty("user.dir"), ".tranforlanguage-runtimes");
    }

    /** Downloads (once) and verifies every artifact, returning their local files in order. */
    public static synchronized List<File> resolve(List<Artifact> artifacts, Logger logger) throws IOException {
        List<File> files = new ArrayList<>();
        for (Artifact artifact : artifacts) files.add(resolve(artifact, logger));
        return files;
    }

    private static File resolve(Artifact artifact, Logger logger) throws IOException {
        File target = new File(storeDirectory(), artifact.path());
        if (target.isFile() && artifact.sha256.equals(sha256(target))) return target;
        Files.createDirectories(target.toPath().getParent());
        IOException last = null;
        for (String repository : repositories()) {
            File partial = new File(target.getPath() + ".part");
            try {
                download(new URL(repository + artifact.path()), partial);
                String digest = sha256(partial);
                if (!artifact.sha256.equals(digest))
                    throw new IOException("SHA-256 mismatch for " + artifact + " from " + repository + ": " + digest);
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("Downloaded runtime library " + artifact);
                return target;
            } catch (IOException failure) {
                last = failure;
                Files.deleteIfExists(partial.toPath());
            }
        }
        throw new IOException("Cannot download " + artifact + (last == null ? "" : ": " + last.getMessage()), last);
    }

    private static List<String> repositories() {
        String configured = System.getProperty("tranforlanguage.runtime.repositories");
        if (configured == null || configured.trim().isEmpty()) return DEFAULT_REPOSITORIES;
        List<String> list = new ArrayList<>();
        for (String entry : configured.split(",")) {
            String url = entry.trim();
            if (!url.isEmpty()) list.add(url.endsWith("/") ? url : url + "/");
        }
        return list;
    }

    private static void download(URL url, File target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "TranForLanguage");
        int status = connection.getResponseCode();
        if (status != 200) throw new IOException("HTTP " + status + " for " + url);
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { connection.disconnect(); }
    }

    static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

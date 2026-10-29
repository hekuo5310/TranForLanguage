package com.github.tranforcpp.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** Owns independent program processes. Preparation is serialized off the game thread. */
public final class ProgramManager implements AutoCloseable {
    private final Platform platform;
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TranForLanguage-Programs"); t.setDaemon(true); return t;
    });
    private volatile Map<String, Entry> programs = Collections.emptyMap();
    private volatile boolean closed;
    private final java.util.Set<CompletableFuture<Void>> pending = java.util.Collections.newSetFromMap(new ConcurrentHashMap<CompletableFuture<Void>, Boolean>());
    public ProgramManager(Platform platform) { this.platform = platform; }
    public synchronized CompletableFuture<Void> reload() { return submit(() -> {
        Map<String, File> discovered = discover();
        Map<String, Entry> next = new LinkedHashMap<>();
        for (Map.Entry<String, File> item : discovered.entrySet()) {
            Entry old = programs.get(item.getKey());
            next.put(item.getKey(), old == null ? new Entry(item.getKey(), item.getValue()) : old);
        }
        for (Entry old : programs.values()) if (!next.containsKey(old.id)) old.stop();
        programs = Collections.unmodifiableMap(next);
        List<String> failures = new ArrayList<>();
        for (Entry entry : next.values()) {
            if (closed || Thread.currentThread().isInterrupted()) break;
            try { entry.start(); } catch (Exception failure) { failures.add(entry.id + ": " + entry.failure); }
        }
        if (!failures.isEmpty()) throw new IllegalStateException(String.join("; ", failures));
    }); }
    public synchronized CompletableFuture<Void> reload(String id) { return submit(() -> {
        Entry entry = programs.get(id);
        if (entry == null) {
            File directory = discover().get(id);
            if (directory == null) throw new IllegalArgumentException("Unknown program: " + id);
            entry = new Entry(id, directory);
            Map<String, Entry> next = new LinkedHashMap<>(programs); next.put(id, entry);
            programs = Collections.unmodifiableMap(next);
        }
        entry.start();
    }); }
    public synchronized CompletableFuture<Void> stop(String id) { return submit(() -> require(id).stop()); }
    private CompletableFuture<Void> submit(CheckedAction action) {
        if (closed) throw new IllegalStateException("Program manager closed");
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            if (closed) return;
            try { action.run(); } catch (Exception e) { throw new CompletionException(e); }
        }, control);
        pending.add(future); future.whenComplete((v, error) -> pending.remove(future));
        return future;
    }
    private Entry require(String id) {
        Entry entry = programs.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown program: " + id + "; reload to discover new directories");
        return entry;
    }
    public List<Status> statuses() {
        List<Status> result = new ArrayList<>();
        for (Entry e : programs.values()) result.add(new Status(e.id, e.state(), e.failure));
        return Collections.unmodifiableList(result);
    }
    public boolean isRunning() { for (Entry e : programs.values()) if (e.running()) return true; return false; }
    public void sendEvent(String name, Object... args) {
        String line = null;
        for (Entry e : programs.values()) {
            ProgramRuntime runtime = e.runtime;
            if (runtime != null && runtime.isRunning() && (e.events == null || e.events.contains(name))) {
                if (line == null) line = ProgramRuntime.eventLine(name, args);
                runtime.sendLine(line);
            }
        }
    }
    private Map<String, File> discover() throws IOException {
        File root = platform.directory().getCanonicalFile(); Files.createDirectories(root.toPath());
        Map<String, File> found = new LinkedHashMap<>();
        if (hasProgram(root)) found.put("@root", root);
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) throw new IOException("Cannot list program directory: " + root);
        Arrays.sort(dirs);
        String reserved = found.containsKey("@root") ? rootEntrypointDirectory(root) : null;
        for (File dir : dirs) if (!dir.getName().startsWith(".") && !dir.getName().startsWith("@")
            && !dir.getName().equals(reserved) && !Files.isSymbolicLink(dir.toPath()) && hasProgram(dir)) found.put(dir.getName(), dir);
        return found;
    }
    private String rootEntrypointDirectory(File root) {
        File manifest = new File(root, "program.json");
        if (!manifest.isFile()) return null;
        try (Reader reader = Files.newBufferedReader(manifest.toPath(), StandardCharsets.UTF_8)) {
            JsonObject config = new Gson().fromJson(reader, JsonObject.class);
            if (config == null || !config.has("entrypoint") || !config.get("entrypoint").isJsonPrimitive()
                || !config.getAsJsonPrimitive("entrypoint").isString()) return null;
            java.nio.file.Path source = new File(root, config.get("entrypoint").getAsString()).getCanonicalFile().toPath();
            if (!source.startsWith(root.toPath()) || !Files.isRegularFile(source)) return null;
            java.nio.file.Path relative = root.toPath().relativize(source);
            return relative.getNameCount() > 1 ? relative.getName(0).toString() : null;
        } catch (IOException | RuntimeException invalid) {
            // A broken root manifest must not hide unrelated child programs.
            return null;
        }
    }
    private boolean hasProgram(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.isFile() && (f.getName().equals("program.json")
            || f.getName().equals("main.py") || f.getName().equals("main.rs")
            || f.getName().endsWith(".c") || f.getName().endsWith(".cpp"))) return true;
        return false;
    }
    @Override public void close() {
        synchronized (this) { closed = true; control.shutdownNow(); }
        for (CompletableFuture<Void> future : pending) future.cancel(false);
        for (Entry e : programs.values()) e.stop();
    }
    private interface CheckedAction { void run() throws Exception; }
    public static final class Status {
        public final String id, state, failure;
        Status(String id, String state, String failure) { this.id = id; this.state = state; this.failure = failure; }
        @Override public String toString() { return id + " [" + state + "]"; }
    }
    private final class Entry {
        final String id; final File directory;
        volatile ProgramRuntime runtime;
        volatile Set<String> events;
        volatile String failure;
        volatile boolean preparing;
        Entry(String id, File directory) { this.id = id; this.directory = directory; }
        boolean running() { ProgramRuntime r = runtime; return r != null && r.isRunning(); }
        String state() { return preparing ? "STARTING" : failure != null ? "FAILED" : running() ? "RUNNING" : "STOPPED"; }
        void start() throws Exception {
            stop(); preparing = true; failure = null;
            try {
                Set<String> selected = null;
                File manifest = new File(directory, "program.json");
                if (manifest.isFile()) try (Reader reader = Files.newBufferedReader(manifest.toPath(), StandardCharsets.UTF_8)) {
                    JsonObject config = new Gson().fromJson(reader, JsonObject.class);
                    if (config != null && config.has("events")) {
                        selected = new HashSet<>();
                        for (JsonElement event : config.getAsJsonArray("events")) {
                            if (!event.isJsonPrimitive() || !event.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("events must contain strings");
                            selected.add(event.getAsString());
                        }
                    }
                }
                events = selected == null ? null : Collections.unmodifiableSet(selected);
                Logger log = new Logger(platform.logger().getName() + ".program." + id, null) {
                    @Override public void log(java.util.logging.Level level, String message) {
                        java.util.logging.LogRecord record = new java.util.logging.LogRecord(level, message);
                        record.setLoggerName(getName()); log(record);
                    }
                    @Override public void log(java.util.logging.LogRecord record) {
                        if (!isLoggable(record.getLevel())) return;
                        java.util.logging.LogRecord copy = new java.util.logging.LogRecord(record.getLevel(), "[" + id + "] " + record.getMessage());
                        copy.setLoggerName(record.getLoggerName() == null ? getName() : record.getLoggerName());
                        copy.setParameters(record.getParameters()); copy.setThrown(record.getThrown());
                        copy.setResourceBundle(record.getResourceBundle()); copy.setResourceBundleName(record.getResourceBundleName());
                        copy.setSourceClassName(record.getSourceClassName()); copy.setSourceMethodName(record.getSourceMethodName());
                        platform.logger().log(copy);
                    }
                };
                log.setLevel(java.util.logging.Level.ALL);
                ProgramRuntime next = new ProgramRuntime(new Platform() {
                    public File directory() { return directory; }
                    public Logger logger() { return log; }
                    public void execute(Runnable task) { platform.execute(task); }
                    public void broadcast(String message) { platform.broadcast(message); }
                    public void sendMessage(String player, String message) { platform.sendMessage(player, message); }
                    public void dispatchCommand(String command) { platform.dispatchCommand(command); }
                });
                synchronized (this) {
                    if (closed) { next.close(); return; }
                    runtime = next;
                }
                next.reload().get();
                if (closed) next.close();
            } catch (Exception error) {
                Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
                failure = cause.toString();
                ProgramRuntime r = runtime; if (r != null) r.close();
                platform.logger().warning("Program " + id + " failed: " + failure);
                throw error;
            } finally { preparing = false; }
        }
        synchronized void stop() {
            ProgramRuntime r = runtime; runtime = null; failure = null;
            if (r != null) r.close();
        }
    }
}

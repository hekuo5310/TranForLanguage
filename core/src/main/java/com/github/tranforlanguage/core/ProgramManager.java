package com.github.tranforlanguage.core;

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
    private final ScheduledExecutorService updates = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TranForLanguage-Updates"); t.setDaemon(true); return t;
    });
    private volatile Map<String, Entry> programs = Collections.emptyMap();
    private volatile boolean closed;
    private final java.util.Set<CompletableFuture<Void>> pending = java.util.Collections.newSetFromMap(new ConcurrentHashMap<CompletableFuture<Void>, Boolean>());
    private final ProgramBus bus;
    public ProgramManager(Platform platform) {
        this.platform = platform;
        // Translators for in-JVM programs are downloaded once into a hidden folder next to the programs.
        com.github.tranforlanguage.core.jvm.RuntimeLibraries.store(new java.io.File(platform.directory(), ".runtimes"));
        bus = new ProgramBus(new ProgramBus.Directory() {
            public ProgramBus.Endpoint find(String id) {
                Entry entry = programs.get(id); ProgramRuntime runtime = entry == null ? null : entry.runtime;
                return runtime == null ? null : runtime.endpoint();
            }
            public Collection<ProgramBus.Endpoint> subscribers(ProgramBus.Endpoint source) {
                List<ProgramBus.Endpoint> result = new ArrayList<>();
                for (Entry entry : programs.values()) {
                    ProgramRuntime runtime = entry.runtime; ProgramBus.Endpoint endpoint = runtime == null ? null : runtime.endpoint();
                    Set<String> events = entry.events;
                    if (endpoint != null && endpoint != source && (events == null || events.contains("message"))) result.add(endpoint);
                }
                return result;
            }
        }, (source, method, args, call) -> platform.execute(() -> {
            // Game API access happens here, on the server thread; failures become error replies.
            // A program stopped or reloaded after queueing the call must not change the world any more.
            if (!source.alive()) { call.done(null, "program " + source.id() + " stopped before the server call ran"); return; }
            // A call that already timed out was answered with an error; running it now could duplicate a retry.
            if (!call.begin()) return;
            String result;
            try { result = platform.serverCall(method, args); }
            catch (Exception failure) {
                String message = failure.getMessage();
                call.done(null, message == null || message.isEmpty() ? failure.toString() : message);
                return;
            }
            call.done(result == null ? "" : result, null);
        }), platform.logger());
    }
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
    /** Fetch a Git-managed program now; reloads it when the fetched commit differs from the running one. */
    CompletableFuture<Void> update(String id) {
        Entry entry = require(id);
        synchronized (this) {
            if (closed) throw new IllegalStateException("Program manager closed");
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    if (updateOnce(entry, null)) reload(id).get();
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }, updates);
            pending.add(future);
            future.whenComplete((value, failure) -> pending.remove(future));
            return future;
        }
    }
    private boolean updateOnce(Entry entry, Object token) throws Exception {
        synchronized (entry.repositoryLock) {
            if (closed || programs.get(entry.id) != entry) return false;
            if (token != null && entry.updateToken != token) return false;
            ProgramRepository repository = entry.repository;
            if (repository == null) throw new IllegalStateException("Program " + entry.id + " has no repository configured");
            return repository.sync(entry.directory);
        }
    }
    private void scheduleUpdate(final Entry entry, ProgramRepository repository, boolean freshCheckout) {
        ScheduledFuture<?> previous;
        synchronized (entry) {
            entry.updateToken = null;
            previous = entry.updateTask;
            entry.updateTask = null;
        }
        if (previous != null) previous.cancel(false);
        if (closed || repository == null || repository.intervalSeconds <= 0) return;
        final Object token = new Object();
        long delay = freshCheckout ? repository.intervalSeconds : 0;
        try {
            synchronized (entry) {
                entry.updateToken = token;
                entry.updateTask = updates.scheduleWithFixedDelay(
                    () -> checkForUpdate(entry, token), delay, repository.intervalSeconds, TimeUnit.SECONDS);
            }
        } catch (RejectedExecutionException shuttingDown) {
            synchronized (entry) { if (entry.updateToken == token) entry.updateToken = null; }
        }
    }
    private void checkForUpdate(Entry entry, Object token) {
        if (!isCurrent(entry, token)) return;
        boolean changed;
        try {
            changed = updateOnce(entry, token);
        } catch (Exception failure) {
            if (isCurrent(entry, token))
                platform.logger().warning("Program " + entry.id + " repository update failed: " + failureMessage(failure));
            return;
        }
        if (!changed || !isCurrent(entry, token)) return;
        platform.logger().info("Program " + entry.id + " updated from repository; reloading");
        try {
            reloadForUpdate(entry, token).whenComplete((value, failure) -> {
                if (failure != null) platform.logger().warning(
                    "Program " + entry.id + " reload after repository update failed: " + failureMessage(failure));
            });
        } catch (RuntimeException stopping) {
            platform.logger().warning("Program " + entry.id + " could not reload after repository update: " + stopping);
        }
    }
    private CompletableFuture<Void> reloadForUpdate(final Entry entry, final Object token) {
        return submit(() -> {
            // Re-check on the control thread so a concurrent stop(id) cannot be followed by a start.
            if (!isCurrent(entry, token)) return;
            entry.start();
        });
    }
    private boolean isCurrent(Entry entry, Object token) {
        return !closed && entry.updateToken == token && programs.get(entry.id) == entry;
    }
    private static String failureMessage(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.toString() : message;
    }
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
            if (runtime != null && runtime.isRunning() && e.accepts(name)) {
                if (line == null) line = ProgramRuntime.eventLine(name, args);
                runtime.sendLine(line);
            }
        }
    }
    /** True when at least one running program explicitly subscribed to the generic Spigot event stream. */
    public boolean wantsSpigotEvents() {
        for (Entry e : programs.values()) {
            ProgramRuntime runtime = e.runtime;
            Set<String> events = e.events;
            if (runtime != null && runtime.isRunning() && events != null && events.contains("BukkitEvent")) return true;
        }
        return false;
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
            || com.github.tranforlanguage.core.language.LanguageProviders.detectLanguage(f.getName()) != null)) return true;
        return false;
    }
    @Override public void close() {
        synchronized (this) { closed = true; control.shutdownNow(); updates.shutdownNow(); }
        for (CompletableFuture<Void> future : pending) future.cancel(false);
        for (Entry e : programs.values()) e.stop();
        bus.close();
    }
    private interface CheckedAction { void run() throws Exception; }
    public static final class Status {
        public final String id, state, failure;
        Status(String id, String state, String failure) { this.id = id; this.state = state; this.failure = failure; }
        @Override public String toString() { return id + " [" + state + "]"; }
    }
    private final class Entry {
        final String id; final File directory;
        final Object repositoryLock = new Object();
        volatile ProgramRuntime runtime;
        volatile Set<String> events;
        volatile String failure;
        volatile boolean preparing;
        volatile ProgramRepository repository;
        private ScheduledFuture<?> updateTask;
        private Object updateToken;
        Entry(String id, File directory) { this.id = id; this.directory = directory; }
        boolean running() { ProgramRuntime r = runtime; return r != null && r.isRunning(); }
        /** "BukkitEvent" is opt-in; it must be listed explicitly even when no events filter is set. */
        boolean accepts(String event) {
            if (event.equals("BukkitEvent")) return events != null && events.contains("BukkitEvent");
            return events == null || events.contains(event);
        }
        String state() { return preparing ? "STARTING" : failure != null ? "FAILED" : running() ? "RUNNING" : "STOPPED"; }
        private JsonObject manifest() throws IOException {
            File file = new File(directory, "program.json");
            if (!file.isFile()) return null;
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                return new Gson().fromJson(reader, JsonObject.class);
            } catch (JsonParseException invalid) {
                throw new IOException("Invalid program.json: " + invalid.getMessage(), invalid);
            }
        }
        void start() throws Exception {
            stop(); preparing = true; failure = null;
            ProgramRepository source = null;
            boolean fresh = false;
            try {
                // Git updates must not replace files while this program is being prepared.
                synchronized (repositoryLock) {
                    if (closed) return;
                    repository = null;
                    JsonObject local = manifest();
                    source = ProgramRepository.parse(local);
                    repository = source;
                    if (source != null && !ProgramRepository.ready(directory)) {
                        source.sync(directory);
                        fresh = true;
                        if (closed) return;
                        platform.logger().info("Program " + id + " checked out from " + source.displayUrl());
                    }
                    Set<String> selected = null;
                    JsonObject config = manifest();
                    if (config == null) config = local;
                    if (config != null && config.has("events")) {
                        selected = new HashSet<>();
                        for (JsonElement event : config.getAsJsonArray("events")) {
                            if (!event.isJsonPrimitive() || !event.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("events must contain strings");
                            selected.add(event.getAsString());
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
                    }, bus, id);
                    synchronized (this) {
                        if (closed) { next.close(); return; }
                        runtime = next;
                    }
                    next.reload().get();
                    if (closed) next.close();
                }
            } catch (Exception error) {
                Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
                failure = cause.toString();
                ProgramRuntime r = runtime; if (r != null) r.close();
                platform.logger().warning("Program " + id + " failed: " + failure);
                throw error;
            } finally {
                preparing = false;
                scheduleUpdate(this, source, fresh);
            }
        }
        synchronized void stop() {
            updateToken = null;
            if (updateTask != null) { updateTask.cancel(false); updateTask = null; }
            ProgramRuntime r = runtime; runtime = null; failure = null;
            if (r != null) r.close();
        }
    }
}

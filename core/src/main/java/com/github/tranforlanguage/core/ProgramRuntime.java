package com.github.tranforlanguage.core;

import com.google.gson.*;
import com.github.tranforlanguage.core.language.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One ordered writer per process; bounded queues; no game API on IO threads. */
public final class ProgramRuntime implements AutoCloseable {
    private static final String SHUTDOWN = "{\"event\":\"shutdown\",\"args\":[]}";
    private static final long DROP_WARNING_INTERVAL = TimeUnit.SECONDS.toNanos(5);
    private final Platform platform;
    private final LanguageProviders languages = new LanguageProviders();
    public LanguageProviders languages() { return languages; }
    private static final Gson gson = new Gson();
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(r -> daemon(r, "TranForLanguage-Program-Lifecycle"));
    private final AtomicLong generation = new AtomicLong();
    private volatile Session session;
    private volatile boolean closed;
    private final java.util.Set<CompletableFuture<Void>> pending = java.util.Collections.newSetFromMap(new ConcurrentHashMap<CompletableFuture<Void>, Boolean>());
    private final ProgramBus bus;
    private final String id;
    public ProgramRuntime(Platform platform) { this(platform, null, "@local"); }
    /** A runtime whose {@code call} / {@code reply} / {@code publish} actions are routed by {@code bus}. */
    public ProgramRuntime(Platform platform, ProgramBus bus, String id) { this.platform = platform; this.bus = bus; this.id = id; }
    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name); t.setDaemon(true); return t;
    }
    public synchronized CompletableFuture<Void> reload() {
        if (closed) throw new IllegalStateException("Runtime closed");
        final long expected = generation.incrementAndGet();
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            stopSession();
            try {
                LaunchCommand command = languages.prepare(platform.directory(), platform.logger());
                synchronized (this) {
                    if (command == null || closed || generation.get() != expected) return;
                    Process process = command.start(java.util.Collections.singletonMap("TRANFORLANGUAGE_PROGRAM_ID", id));
                    if (command.inJvm()) platform.logger().info("Running translated program inside the server JVM");
                    Session next = new Session(process, expected);
                    session = next;
                    next.start();
                }
            } catch (Exception e) { throw new CompletionException(e); }
        }, lifecycle);
        pending.add(future); future.whenComplete((v, error) -> pending.remove(future));
        return future;
    }
    public boolean isRunning() { Session s = session; return s != null && s.process.isAlive(); }
    /** The live session as a bus endpoint, or null when not running. */
    ProgramBus.Endpoint endpoint() { Session s = session; return s != null && s.process.isAlive() ? s.endpoint : null; }
    public void sendEvent(String name, Object... args) {
        if (isRunning()) sendLine(eventLine(name, args));
    }
    static String eventLine(String name, Object... args) {
        JsonObject event = new JsonObject(); event.addProperty("event", name);
        JsonArray values = new JsonArray();
        for (Object arg : args) values.add(arg == null ? "null" : String.valueOf(arg));
        event.add("args", values);
        return gson.toJson(event);
    }
    void sendLine(String line) {
        Session s = session;
        if (s == null || !s.process.isAlive()) return;
        if (!s.queue.offer(line)) {
            s.droppedSinceWarning.incrementAndGet();
            s.reportDroppedEvents(false);
        }
    }
    private void handle(String line, Session source) {
        try {
            JsonObject message = gson.fromJson(line, JsonObject.class);
            String action = message.get("action").getAsString();
            if (action.equals("call") || action.equals("reply") || action.equals("publish")) {
                // Inter-program traffic stays on IO threads; it never needs the game thread.
                if (closed || session != source) return;
                if (bus != null) bus.receive(source.endpoint, message);
                else if (action.equals("call") && message.has("id")) {
                    JsonObject reply = new JsonObject(); reply.addProperty("event", "reply"); reply.add("args", new JsonArray());
                    reply.add("id", message.get("id")); reply.addProperty("ok", false);
                    reply.addProperty("error", "inter-program calls require a program manager");
                    source.endpoint.deliver(gson.toJson(reply));
                }
                return;
            }
            platform.execute(() -> {
                if (closed || session != source || generation.get() != source.generation) return;
                try {
                    switch (action) {
                        case "broadcast": platform.broadcast(message.get("message").getAsString()); break;
                        case "sendMessage": platform.sendMessage(message.get("player").getAsString(), message.get("message").getAsString()); break;
                        case "console": platform.logger().info(message.get("message").getAsString()); break;
                        case "dispatchCommand":
                        case "executeCommand": platform.dispatchCommand(message.get("command").getAsString()); break;
                        default: platform.logger().warning("Unknown Program action: " + action);
                    }
                } catch (RuntimeException e) { platform.logger().warning("Invalid Program action: " + e); }
            });
        } catch (RuntimeException e) { platform.logger().warning("Invalid Program JSON: " + line); }
    }
    private void stopSession() {
        Session old = session; session = null;
        if (old != null) old.close();
    }
    @Override public void close() {
        synchronized (this) { closed = true; generation.incrementAndGet(); }
        lifecycle.shutdownNow();
        for (CompletableFuture<Void> future : pending) future.cancel(false);
        stopSession();
    }
    private final class Session {
        final Process process;
        final long generation;
        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(2000);
        final AtomicLong droppedSinceWarning = new AtomicLong();
        final AtomicLong lastDropWarning = new AtomicLong(System.nanoTime() - DROP_WARNING_INTERVAL);
        final BufferedWriter writer;
        final Thread sender;
        volatile boolean stopped;
        final ProgramBus.Endpoint endpoint = new ProgramBus.Endpoint() {
            public String id() { return id; }
            public boolean deliver(String line) {
                return alive() && queue.offer(line);
            }
            // Same guard as ordinary actions: close() marks the runtime closed and bumps the generation before
            // it clears the session, so the session reference alone is not enough.
            @Override public boolean alive() {
                return !ProgramRuntime.this.closed && ProgramRuntime.this.generation.get() == Session.this.generation && session == Session.this && !stopped && process.isAlive();
            }
            @Override public String toString() { return id; }
        };
        Session(Process process, long generation) {
            this.process = process; this.generation = generation;
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            sender = daemon(() -> {
                try {
                    while (process.isAlive()) {
                        String line = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (line != null) { writer.write(line); writer.newLine(); writer.flush();
                            if (line.equals(SHUTDOWN)) break;
                        }
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (IOException e) { if (!stopped) platform.logger().warning("Program writer: " + e); }
            }, "TranForLanguage-Program-Writer");
        }
        void start() {
            sender.start();
            daemon(() -> {
                try {
                    int code = process.waitFor();
                    if (bus != null) bus.ended(endpoint);
                    if (!stopped) platform.logger().warning("Program exited unexpectedly with code " + code
                        + "; use reload to restart (no automatic restart)");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "TranForLanguage-Program-Exit").start();
            daemon(() -> read(process.getInputStream(), false), "TranForLanguage-Program-Reader").start();
            daemon(() -> read(process.getErrorStream(), true), "TranForLanguage-Program-Stderr").start();
        }
        void read(InputStream stream, boolean error) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (error) platform.logger().warning("Program: " + line); else handle(line, this);
                }
            } catch (IOException e) { if (!stopped) platform.logger().warning("Program reader: " + e); }
        }
        void reportDroppedEvents(boolean closing) {
            long now = System.nanoTime();
            long previous = lastDropWarning.get();
            if (closing || (now - previous >= DROP_WARNING_INTERVAL && lastDropWarning.compareAndSet(previous, now))) {
                long count = droppedSinceWarning.getAndSet(0);
                if (count != 0) platform.logger().warning("Program event queue full; dropped " + count + " event(s)");
            }
        }
        void close() {
            reportDroppedEvents(true);
            stopped = true; queue.clear(); queue.offer(SHUTDOWN);
            if (bus != null) bus.ended(endpoint);
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
            finally {
                sender.interrupt();
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }
}

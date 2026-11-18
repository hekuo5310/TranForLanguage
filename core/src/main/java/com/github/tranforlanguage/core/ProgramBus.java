package com.github.tranforlanguage.core;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Routes calls, replies and published messages between independent program processes.
 * Routing runs on program IO threads and never touches game APIs. Every routed id is
 * rewritten so programs cannot answer or observe calls that were not sent to them.
 */
public final class ProgramBus implements AutoCloseable {
    /** One live process session. Equality is identity: a restarted program is a new endpoint. */
    public interface Endpoint {
        String id();
        /** Queue a protocol line; false when the session is gone or its queue is full. */
        boolean deliver(String line);
        /** Whether the session is still the live one for its program (not stopped or reloaded). */
        default boolean alive() { return true; }
    }
    /** Resolves program IDs to live sessions. */
    public interface Directory {
        Endpoint find(String id);
        /** Live sessions that accept the {@code message} event, excluding {@code source}. */
        Collection<Endpoint> subscribers(Endpoint source);
    }
    /** Executes {@code @server} calls; {@code done} receives a result or an error message. */
    public interface Server {
        /** {@code source} is the calling session; implementations must skip it once it is no longer alive. */
        void call(Endpoint source, String method, List<String> args, java.util.function.BiConsumer<String, String> done);
    }
    /** Reserved call target answered by the platform instead of a program. */
    public static final String SERVER = "@server";
    private static final Endpoint SERVER_ENDPOINT = new Endpoint() {
        public String id() { return SERVER; }
        public boolean deliver(String line) { return false; }
    };
    static final int MAX_PENDING = 10000;
    private static final Gson gson = new Gson();
    private final Directory directory;
    private final Server server;
    private final Logger logger;
    private final long timeoutMillis;
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TranForLanguage-Calls"); t.setDaemon(true); return t;
    });
    private volatile boolean closed;

    public ProgramBus(Directory directory, Logger logger) { this(directory, null, logger); }
    public ProgramBus(Directory directory, Server server, Logger logger) {
        this(directory, server, logger, Long.getLong("tranforlanguage.call-timeout-ms", 30000L));
    }
    ProgramBus(Directory directory, Logger logger, long timeoutMillis) { this(directory, null, logger, timeoutMillis); }
    ProgramBus(Directory directory, Server server, Logger logger, long timeoutMillis) {
        this.directory = directory; this.server = server; this.logger = logger; this.timeoutMillis = Math.max(1, timeoutMillis);
    }

    /** Handles {@code call}, {@code reply} and {@code publish} actions from {@code source}. */
    public void receive(Endpoint source, JsonObject message) {
        String action = string(message, "action");
        if ("call".equals(action)) call(source, message);
        else if ("reply".equals(action)) reply(source, message);
        else if ("publish".equals(action)) publish(source, message);
        else throw new IllegalArgumentException("Not a bus action: " + action);
    }

    public int pendingCalls() { return pending.size(); }

    private void call(Endpoint source, JsonObject message) {
        JsonElement callerId = message.has("id") && !message.get("id").isJsonNull() ? message.get("id") : null;
        String target = string(message, "target"), method = string(message, "method");
        if (target == null || target.isEmpty() || method == null || method.isEmpty()) {
            fail(source, callerId, target, "call requires non-empty string target and method"); return;
        }
        JsonArray args = arguments(message);
        if (closed) { fail(source, callerId, target, "program manager closed"); return; }
        if (SERVER.equals(target)) { serverCall(source, callerId, method, args); return; }
        Endpoint destination = directory.find(target);
        if (destination == null) { fail(source, callerId, target, "program is not running: " + target); return; }
        JsonObject line = event("call", args); line.addProperty("from", source.id()); line.addProperty("method", method);
        if (callerId == null) {
            if (!destination.deliver(gson.toJson(line))) logger.warning("Dropped notification " + method + " to " + target + ": queue full");
            return;
        }
        if (pending.size() >= MAX_PENDING) { fail(source, callerId, target, "too many pending calls"); return; }
        long routed = ids.incrementAndGet();
        Pending call = new Pending(source, callerId, destination);
        pending.put(routed, call);
        line.addProperty("id", Long.toString(routed));
        try {
            call.timeout = timer.schedule(() -> complete(routed, null, "call to " + target + "." + method + " timed out after "
                + timeoutMillis + " ms"), timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shutdown) { complete(routed, null, "program manager closed"); return; }
        if (closed) { complete(routed, null, "program manager closed"); return; }
        if (!destination.deliver(gson.toJson(line))) complete(routed, null, "program " + target + " cannot accept calls (stopped or queue full)");
    }

    private void serverCall(Endpoint source, JsonElement callerId, String method, JsonArray args) {
        if (server == null) { fail(source, callerId, SERVER, "server calls are not available on this platform"); return; }
        List<String> values = new ArrayList<>();
        for (JsonElement arg : args) values.add(arg.getAsString());
        if (callerId == null) {
            server.call(source, method, Collections.unmodifiableList(values), (result, error) -> {
                if (error != null) logger.warning("Server notification " + method + " from " + source.id() + " failed: " + error);
            });
            return;
        }
        if (pending.size() >= MAX_PENDING) { fail(source, callerId, SERVER, "too many pending calls"); return; }
        long routed = ids.incrementAndGet();
        Pending call = new Pending(source, callerId, SERVER_ENDPOINT);
        pending.put(routed, call);
        try {
            call.timeout = timer.schedule(() -> complete(routed, null, "server call " + method + " timed out after "
                + timeoutMillis + " ms"), timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shutdown) { complete(routed, null, "program manager closed"); return; }
        // close() may have taken its snapshot of pending calls before this one was registered.
        if (closed) { complete(routed, null, "program manager closed"); return; }
        try { server.call(source, method, Collections.unmodifiableList(values), (result, error) -> complete(routed, result, error)); }
        catch (RuntimeException failure) { complete(routed, null, String.valueOf(failure.getMessage())); }
    }

    private void reply(Endpoint source, JsonObject message) {
        String id = string(message, "id");
        Long routed;
        try { routed = id == null ? null : Long.valueOf(id); } catch (NumberFormatException e) { routed = null; }
        Pending call = routed == null ? null : pending.get(routed);
        if (call == null || call.target != source) {
            // Late replies after a timeout are normal; anything else is a program bug worth seeing.
            if (routed == null || routed > ids.get()) logger.warning("Ignored reply with unknown id from " + source.id() + ": " + id);
            return;
        }
        if (message.has("error") && !message.get("error").isJsonNull()) complete(routed, null, text(message.get("error")));
        else complete(routed, message.has("result") ? text(message.get("result")) : "", null);
    }

    private void publish(Endpoint source, JsonObject message) {
        String topic = string(message, "topic");
        if (topic == null || topic.isEmpty()) { logger.warning("publish from " + source.id() + " requires a non-empty topic"); return; }
        JsonObject line = event("message", arguments(message));
        line.addProperty("from", source.id()); line.addProperty("topic", topic);
        String text = gson.toJson(line);
        for (Endpoint target : directory.subscribers(source))
            if (!target.deliver(text)) logger.warning("Dropped message " + topic + " to " + target.id() + ": queue full");
    }

    /** Fails calls waiting on a session that ended and forgets calls it made. */
    public void ended(Endpoint endpoint) {
        for (Map.Entry<Long, Pending> entry : pending.entrySet()) {
            Pending call = entry.getValue();
            if (call.target == endpoint) complete(entry.getKey(), null, "program " + endpoint.id() + " stopped before replying");
            else if (call.caller == endpoint && pending.remove(entry.getKey(), call)) call.cancelTimeout();
        }
    }

    private void complete(long routed, String result, String error) {
        Pending call = pending.remove(routed);
        if (call == null) return;
        call.cancelTimeout();
        send(call.caller, call.callerId, call.target.id(), result, error);
    }

    private void fail(Endpoint source, JsonElement callerId, String target, String error) {
        if (callerId == null) { logger.warning("Dropped notification from " + source.id() + ": " + error); return; }
        send(source, callerId, target == null ? "" : target, null, error);
    }

    private static void send(Endpoint caller, JsonElement callerId, String from, String result, String error) {
        JsonObject line = event("reply", new JsonArray());
        line.add("id", callerId); line.addProperty("from", from); line.addProperty("ok", error == null);
        if (error == null) line.addProperty("result", result); else line.addProperty("error", error);
        caller.deliver(gson.toJson(line));
    }

    private static JsonObject event(String name, JsonArray args) {
        JsonObject line = new JsonObject(); line.addProperty("event", name); line.add("args", args); return line;
    }
    private static JsonArray arguments(JsonObject message) {
        JsonArray result = new JsonArray();
        JsonElement args = message.get("args");
        if (args != null && args.isJsonArray()) for (JsonElement arg : args.getAsJsonArray()) result.add(text(arg));
        return result;
    }
    /** Arguments and results are strings on the wire; structured values keep their JSON text. */
    private static String text(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        return value.isJsonPrimitive() ? value.getAsString() : gson.toJson(value);
    }
    private static String string(JsonObject message, String key) {
        JsonElement value = message.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    @Override public void close() {
        closed = true;
        for (Long id : new ArrayList<>(pending.keySet())) complete(id, null, "program manager closed");
        timer.shutdownNow();
    }

    private static final class Pending {
        final Endpoint caller, target; final JsonElement callerId;
        volatile ScheduledFuture<?> timeout;
        Pending(Endpoint caller, JsonElement callerId, Endpoint target) { this.caller = caller; this.callerId = callerId; this.target = target; }
        void cancelTimeout() { ScheduledFuture<?> t = timeout; if (t != null) t.cancel(false); }
    }
}

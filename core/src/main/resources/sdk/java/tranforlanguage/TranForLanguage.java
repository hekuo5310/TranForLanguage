package tranforlanguage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Protocol-v1 SDK for Java programs, compiled in by the server. {@code System.out} belongs to
 * the protocol; log diagnostics to {@code System.err}.
 *
 * <p>Programs can call each other: {@link #handle} exposes a method, {@link #call} waits for its
 * string result, {@link #publish} / {@link #subscribe} broadcast messages. Call {@link #call} from
 * handlers or the main thread; the SDK reads stdin on the thread that runs {@link #run}.
 */
public final class TranForLanguage {
    private TranForLanguage() {}

    @FunctionalInterface public interface EventHandler { void handle(String... args) throws Exception; }
    @FunctionalInterface public interface Method { Object call(String... args) throws Exception; }
    @FunctionalInterface public interface Subscriber { void receive(String from, String... args) throws Exception; }

    /** A failed call: unknown program or method, remote error, timeout or shutdown. */
    public static final class CallError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public CallError(String message) { super(message); }
    }

    private static final Object OUTPUT = new Object();
    private static final PrintStream out = output();
    private static final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private static final Map<String, List<EventHandler>> handlers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Method> methods = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, List<Subscriber>> topics = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Deque<Map<String, Object>> backlog = new ArrayDeque<>();
    private static final Map<String, Map<String, Object>> replies = new HashMap<>();
    private static long nextId;

    private static volatile Thread runner;
    private static PrintStream output() {
        try { return new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8"); }
        catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    /** This program's ID as the server knows it (directory name or "@root"). */
    public static String programId() { String id = System.getenv("TRANFORLANGUAGE_PROGRAM_ID"); return id == null ? "" : id; }

    public static void broadcast(Object message) { send("broadcast", "message", String.valueOf(message)); }
    public static void sendMessage(Object player, Object message) { send("sendMessage", "player", String.valueOf(player), "message", String.valueOf(message)); }
    public static void console(Object message) { send("console", "message", String.valueOf(message)); }
    public static void dispatchCommand(Object command) { send("executeCommand", "command", String.valueOf(command)); }

    public static void on(String event, EventHandler handler) { handlers.computeIfAbsent(event, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(handler); }
    public static void handle(String method, Method handler) { methods.put(method, handler); }
    public static void subscribe(String topic, Subscriber handler) { topics.computeIfAbsent(topic, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(handler); }

    /** Calls {@code method} on program {@code target} and returns its string result. */
    public static String call(String target, String method, Object... args) {
        Thread reading = runner;
        if (reading != null && reading != Thread.currentThread())
            throw new CallError("TranForLanguage.call must run on the thread running TranForLanguage.run (for example inside a handler)");
        synchronized (TranForLanguage.class) { return await(target, method, args); }
    }
    private static String await(String target, String method, Object... args) {
        String id = Long.toString(++nextId);
        send("call", "target", target, "method", method, "args", strings(args), "id", id);
        while (!replies.containsKey(id)) {
            Map<String, Object> message = read();
            if (message == null) throw new CallError("input closed while waiting for " + target);
            String kind = String.valueOf(message.get("event"));
            if (kind.equals("reply")) replies.put(String.valueOf(message.get("id")), message);
            else if (kind.equals("call")) serve(message); // answer nested calls instead of deadlocking
            else {
                backlog.add(message);
                if (kind.equals("shutdown")) throw new CallError("server is shutting down");
            }
        }
        Map<String, Object> reply = replies.remove(id);
        if (!Boolean.TRUE.equals(reply.get("ok"))) {
            Object error = reply.get("error");
            throw new CallError(error == null ? "call failed" : error.toString());
        }
        Object result = reply.get("result");
        return result == null ? "" : result.toString();
    }

    /** Calls a game action on the server, e.g. {@code server("teleport", "Alex", 0, 64, 0)}. */
    public static String server(String method, Object... args) { return call("@server", method, args); }

    public static void notify(String target, String method, Object... args) {
        send("call", "target", target, "method", method, "args", strings(args));
    }

    public static void publish(String topic, Object... args) { send("publish", "topic", topic, "args", strings(args)); }

    /** Reads events until shutdown, answering calls with {@link #handle} methods. */
    public static synchronized void run() {
        runner = Thread.currentThread();
        try { loop(); } finally { runner = null; }
    }
    private static void loop() {
        for (;;) {
            Map<String, Object> event = backlog.isEmpty() ? read() : backlog.poll();
            if (event == null) return;
            String kind = String.valueOf(event.get("event"));
            if (kind.equals("call")) { serve(event); continue; }
            if (kind.equals("reply")) continue; // a late reply after a timeout
            String[] args = args(event);
            try {
                if (kind.equals("message")) for (Subscriber s : topics.getOrDefault(String.valueOf(event.get("topic")), Collections.<Subscriber>emptyList()))
                    s.receive(event.get("from") == null ? "" : event.get("from").toString(), args);
                for (EventHandler h : handlers.getOrDefault(kind, Collections.<EventHandler>emptyList())) h.handle(args);
            } catch (Exception e) {
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
            }
            if (kind.equals("shutdown")) return;
        }
    }

    private static void serve(Map<String, Object> message) {
        Object id = message.get("id");
        String text;
        boolean ok;
        try {
            Method method = methods.get(String.valueOf(message.get("method")));
            if (method == null) throw new CallError("unknown method " + message.get("method"));
            Object result = method.call(args(message));
            text = result == null ? "" : result.toString(); ok = true;
        } catch (Exception e) {
            text = e.getClass().getSimpleName() + ": " + e.getMessage(); ok = false;
        }
        if (id == null) { if (!ok) System.err.println("tranforlanguage: notification failed: " + text); return; }
        send("reply", "id", id, ok ? "result" : "error", text);
    }

    private static String[] args(Map<String, Object> event) {
        Object value = event.get("args");
        if (!(value instanceof List)) return new String[0];
        List<?> list = (List<?>) value;
        String[] result = new String[list.size()];
        for (int i = 0; i < result.length; i++) result[i] = String.valueOf(list.get(i));
        return result;
    }

    private static List<String> strings(Object... args) {
        List<String> result = new ArrayList<>();
        for (Object arg : args) result.add(String.valueOf(arg));
        return result;
    }

    private static Map<String, Object> read() {
        try {
            for (String line; (line = in.readLine()) != null;) {
                if (line.trim().isEmpty()) continue;
                Object value = new Json(line).value();
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) value;
                    return map;
                }
            }
            return null;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void send(String action, Object... fields) {
        StringBuilder line = new StringBuilder("{\"action\":");
        quote(line, action);
        for (int i = 0; i < fields.length; i += 2) {
            line.append(','); quote(line, (String) fields[i]); line.append(':');
            Object value = fields[i + 1];
            if (value instanceof List) {
                line.append('[');
                List<?> list = (List<?>) value;
                for (int j = 0; j < list.size(); j++) { if (j > 0) line.append(','); quote(line, String.valueOf(list.get(j))); }
                line.append(']');
            } else quote(line, String.valueOf(value));
        }
        line.append('}');
        synchronized (OUTPUT) { out.print(line); out.print('\n'); out.flush(); }
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
            }
        }
        out.append('"');
    }

    /** Minimal JSON reader for protocol lines. */
    private static final class Json {
        private final String s; private int i;
        Json(String s) { this.s = s; }
        Object value() {
            space();
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>(); i++; space();
                if (peek('}')) { i++; return map; }
                for (;;) {
                    space(); String key = string(); space(); expect(':');
                    map.put(key, value()); space();
                    if (peek(',')) { i++; continue; }
                    expect('}'); return map;
                }
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>(); i++; space();
                if (peek(']')) { i++; return list; }
                for (;;) {
                    list.add(value()); space();
                    if (peek(',')) { i++; continue; }
                    expect(']'); return list;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("Invalid JSON at " + i);
            return s.substring(start, i);
        }
        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': out.append('\n'); break; case 't': out.append('\t'); break; case 'r': out.append('\r'); break;
                    case 'b': out.append('\b'); break; case 'f': out.append('\f'); break;
                    case 'u': out.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: out.append(e);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }
        private void space() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
        private void expect(char c) { if (!peek(c)) throw new IllegalArgumentException("Expected " + c + " at " + i); i++; }
    }
}

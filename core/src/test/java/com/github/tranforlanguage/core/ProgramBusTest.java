package com.github.tranforlanguage.core;

import com.google.gson.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.Assert.*;

public class ProgramBusTest {
    static final class Fake implements ProgramBus.Endpoint {
        final String id; final BlockingQueue<JsonObject> lines = new LinkedBlockingQueue<>(); volatile boolean accepting = true;
        Fake(String id) { this.id = id; }
        public String id() { return id; }
        public boolean deliver(String line) { return accepting && lines.add(new Gson().fromJson(line, JsonObject.class)); }
        JsonObject next() throws InterruptedException { JsonObject line = lines.poll(5, TimeUnit.SECONDS); assertNotNull(id + " got nothing", line); return line; }
    }
    private final Map<String, Fake> live = new ConcurrentHashMap<>();
    private ProgramBus bus;
    @Before public void setup() { bus = bus(30000); }
    @After public void close() { bus.close(); }
    private ProgramBus bus(long timeout) {
        return new ProgramBus(new ProgramBus.Directory() {
            public ProgramBus.Endpoint find(String id) { return live.get(id); }
            public Collection<ProgramBus.Endpoint> subscribers(ProgramBus.Endpoint source) {
                List<ProgramBus.Endpoint> all = new ArrayList<>(live.values()); all.remove(source); return all;
            }
        }, Logger.getLogger("bus-test"), timeout);
    }
    private Fake program(String id) { Fake fake = new Fake(id); live.put(id, fake); return fake; }
    private static JsonObject json(String text) { return new Gson().fromJson(text, JsonObject.class); }

    @Test public void routesCallAndReplyWithRewrittenIds() throws Exception {
        Fake a = program("a"), b = program("b");
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"b\",\"method\":\"sum\",\"args\":[\"1\",2,{\"x\":true}],\"id\":\"caller-7\"}"));
        JsonObject call = b.next();
        assertEquals("call", call.get("event").getAsString()); assertEquals("a", call.get("from").getAsString());
        assertEquals("sum", call.get("method").getAsString());
        assertEquals("[\"1\",\"2\",\"{\\\"x\\\":true}\"]", call.get("args").toString());
        assertNotEquals("caller-7", call.get("id").getAsString());
        // Another program cannot answer a call that was not routed to it.
        Fake c = program("c");
        bus.receive(c, json("{\"action\":\"reply\",\"id\":\"" + call.get("id").getAsString() + "\",\"result\":\"spoof\"}"));
        bus.receive(b, json("{\"action\":\"reply\",\"id\":\"" + call.get("id").getAsString() + "\",\"result\":\"3\"}"));
        JsonObject reply = a.next();
        assertEquals("reply", reply.get("event").getAsString()); assertEquals("caller-7", reply.get("id").getAsString());
        assertTrue(reply.get("ok").getAsBoolean()); assertEquals("3", reply.get("result").getAsString());
        assertEquals("b", reply.get("from").getAsString()); assertEquals(0, reply.getAsJsonArray("args").size());
        assertTrue(a.lines.isEmpty()); assertTrue(c.lines.isEmpty()); assertEquals(0, bus.pendingCalls());
    }
    @Test public void errorsTimeoutsAndStoppedTargetsFailCalls() throws Exception {
        Fake a = program("a"), b = program("b");
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"missing\",\"method\":\"m\",\"id\":1}"));
        JsonObject missing = a.next(); assertFalse(missing.get("ok").getAsBoolean()); assertEquals(1, missing.get("id").getAsInt());
        assertEquals("program is not running: missing", missing.get("error").getAsString());
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"b\",\"method\":\"m\",\"id\":\"2\"}"));
        String routed = b.next().get("id").getAsString();
        bus.receive(b, json("{\"action\":\"reply\",\"id\":\"" + routed + "\",\"error\":\"boom\"}"));
        assertEquals("boom", a.next().get("error").getAsString());
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"b\",\"method\":\"m\",\"id\":\"3\"}"));
        b.next(); bus.ended(b);
        assertEquals("program b stopped before replying", a.next().get("error").getAsString());
        b.accepting = false;
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"b\",\"method\":\"m\",\"id\":\"4\"}"));
        assertTrue(a.next().get("error").getAsString().contains("cannot accept calls"));
        assertEquals(0, bus.pendingCalls());
        bus.close(); bus = bus(100); b.accepting = true;
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"b\",\"method\":\"slow\",\"id\":\"5\"}"));
        assertTrue(a.next().get("error").getAsString().contains("timed out"));
        assertEquals(0, bus.pendingCalls());
    }
    @Test public void notificationsHaveNoReplyAndPublishSkipsSender() throws Exception {
        Fake a = program("a"), b = program("b"), c = program("c");
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"b\",\"method\":\"tick\",\"args\":[\"x\"]}"));
        JsonObject note = b.next(); assertFalse(note.has("id")); assertEquals(0, bus.pendingCalls());
        bus.receive(a, json("{\"action\":\"publish\",\"topic\":\"news\",\"args\":[\"中文\"]}"));
        for (Fake receiver : new Fake[]{b, c}) {
            JsonObject message = receiver.next();
            assertEquals("message", message.get("event").getAsString()); assertEquals("news", message.get("topic").getAsString());
            assertEquals("a", message.get("from").getAsString()); assertEquals("中文", message.getAsJsonArray("args").get(0).getAsString());
        }
        assertTrue(a.lines.isEmpty());
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"\",\"method\":\"m\",\"id\":\"9\"}"));
        assertTrue(a.next().get("error").getAsString().contains("non-empty"));
    }
    @Test public void serverTargetIsAnsweredByThePlatform() throws Exception {
        bus.close();
        List<Runnable> serverThread = new CopyOnWriteArrayList<>();
        bus = new ProgramBus(new ProgramBus.Directory() {
            public ProgramBus.Endpoint find(String id) { return live.get(id); }
            public Collection<ProgramBus.Endpoint> subscribers(ProgramBus.Endpoint source) { return Collections.emptyList(); }
        }, (method, args, done) -> serverThread.add(() -> {
            if (method.equals("echo")) done.accept(String.join("|", args), null); else if (!method.equals("hang")) done.accept(null, "bad " + method);
        }), Logger.getLogger("bus-test"), 200);
        Fake a = program("a");
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"@server\",\"method\":\"echo\",\"args\":[\"x\",1],\"id\":\"s1\"}"));
        assertTrue("Answered only when the server thread runs it", a.lines.isEmpty());
        serverThread.remove(0).run();
        JsonObject ok = a.next();
        assertTrue(ok.get("ok").getAsBoolean()); assertEquals("x|1", ok.get("result").getAsString());
        assertEquals("@server", ok.get("from").getAsString()); assertEquals("s1", ok.get("id").getAsString());
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"@server\",\"method\":\"nope\",\"id\":\"s2\"}"));
        serverThread.remove(0).run();
        assertEquals("bad nope", a.next().get("error").getAsString());
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"@server\",\"method\":\"hang\",\"id\":\"s3\"}"));
        assertTrue(a.next().get("error").getAsString().contains("timed out"));
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"@server\",\"method\":\"echo\"}"));
        serverThread.remove(serverThread.size() - 1).run();
        assertTrue("Notifications get no reply", a.lines.poll(300, TimeUnit.MILLISECONDS) == null);
        assertEquals(0, bus.pendingCalls());
        bus.close(); bus = bus(30000);
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"@server\",\"method\":\"echo\",\"id\":\"s4\"}"));
        assertTrue(a.next().get("error").getAsString().contains("not available"));
    }
    @Test public void closingFailsPendingCalls() throws Exception {
        Fake a = program("a"), b = program("b");
        bus.receive(a, json("{\"action\":\"call\",\"target\":\"b\",\"method\":\"m\",\"id\":\"1\"}"));
        b.next(); bus.close();
        assertEquals("program manager closed", a.next().get("error").getAsString());
    }
}

package com.github.tranforcpp.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import static org.junit.Assert.*;

public class ProgramManagerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private RecordingPlatform platform;
    private ProgramManager manager;
    @Before public void setup() throws Exception { platform = new RecordingPlatform(temp.newFolder("mixed programs")); manager = new ProgramManager(platform); }
    @After public void close() { manager.close(); }
    private File write(String name, String text) throws Exception {
        File file = new File(platform.dir, name); Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Runnable action = platform.tasks.poll(20, TimeUnit.MILLISECONDS); if (action != null) action.run();
        }
        assertTrue("Timed out: " + platform.messages + " / " + manager.statuses(), condition.getAsBoolean());
    }
    private void python(String id, String extra) throws Exception {
        write(id + "/program.json", "{\"language\":\"python\"" + extra + "}");
        write(id + "/main.py", "from tranforcpp import on,run,broadcast\n@on('PlayerJoin')\ndef joined(p): broadcast('"+id+":'+p)\nrun()\n");
    }
    @Test public void fourLanguagesRunTogetherAndRestartIndependently() throws Exception {
        write("main.cpp", "int main(){std::string s;while(std::getline(std::cin,s)){if(s.find(\"shutdown\")!=std::string::npos)break;broadcast((\"cpp:\"+s).c_str());}}\n");
        write("native/main.c", "#include <string.h>\nint main(void){char s[8192],out[8300];while(fgets(s,sizeof(s),stdin)){if(strstr(s,\"shutdown\"))break;snprintf(out,sizeof(out),\"c:%s\",s);broadcast(out);}return 0;}\n");
        python("python", "");
        write("rust/main.rs", "use tranforcpp::{run,broadcast};fn main(){run(|e|{if e.event==\"PlayerJoin\"{broadcast(&format!(\"rust:{}\",e.args[0])).unwrap();}}).unwrap();}\n");
        manager.reload().get(310, TimeUnit.SECONDS);
        assertEquals(4, manager.statuses().size());
        assertTrue(manager.statuses().stream().allMatch(s -> s.state.equals("RUNNING")));
        String value = "中文\"\\\n";
        manager.sendEvent("PlayerJoin", value);
        await(() -> platform.messages.size() == 4);
        assertTrue(platform.messages.contains("python:"+value)); assertTrue(platform.messages.contains("rust:"+value));
        assertTrue(platform.messages.stream().anyMatch(s -> s.startsWith("cpp:") && s.contains("PlayerJoin")));
        assertTrue(platform.messages.stream().anyMatch(s -> s.startsWith("c:") && s.contains("PlayerJoin")));
        manager.stop("native").get(5, TimeUnit.SECONDS);
        manager.reload("python").get(10, TimeUnit.SECONDS);
        platform.messages.clear(); manager.sendEvent("PlayerJoin", "second");
        await(() -> platform.messages.size() == 3);
        assertFalse(platform.messages.stream().anyMatch(s -> s.startsWith("c:")));
        manager.reload("native").get(20, TimeUnit.SECONDS);
        platform.messages.clear(); manager.sendEvent("PlayerJoin", "third");
        await(() -> platform.messages.size() == 4);
    }
    @Test public void brokenProgramDoesNotBlockOthersAndSubscriptionsFilterEvents() throws Exception {
        python("good", ",\"events\":[\"PlayerJoin\"]");
        write("bad/main.c", "not valid C");
        try { manager.reload().get(20, TimeUnit.SECONDS); fail(); } catch (ExecutionException expected) {}
        assertTrue(manager.statuses().stream().anyMatch(s -> s.id.equals("bad") && s.state.equals("FAILED") && s.failure != null));
        assertTrue(manager.isRunning());
        manager.sendEvent("PlayerJoin", "ok"); await(() -> platform.messages.contains("good:ok"));
        manager.stop("bad").get(5, TimeUnit.SECONDS);
        try (java.util.stream.Stream<Path> paths = Files.walk(new File(platform.dir, "bad").toPath())) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
        manager.reload().get(10, TimeUnit.SECONDS);
        assertEquals(1, manager.statuses().size());
        // An uninterested program is not even sent the invalid event's payload.
        manager.sendEvent("ignored", new Object() { public String toString() { throw new AssertionError("Unsubscribed event serialized"); } });
    }
    @Test public void exitCodeIsLoggedAndProgramIsNotRestartedAutomatically() throws Exception {
        List<String> exits = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            public void publish(LogRecord record) { if (record.getMessage().contains("exited unexpectedly")) exits.add(record.getLoggerName()+" "+record.getMessage()); }
            public void flush() {} public void close() {}
        };
        platform.log.addHandler(handler);
        try {
            write("program.json", "{\"language\":\"process\",\"command\":[\"python3\",\"-c\",\"raise SystemExit(7)\"]}");
            manager.reload().get(10, TimeUnit.SECONDS);
            await(() -> !exits.isEmpty());
            assertTrue(exits.get(0), exits.get(0).contains("@root") && exits.get(0).contains("code 7"));
            assertFalse(manager.isRunning()); assertEquals("STOPPED", manager.statuses().get(0).state);
            assertEquals(1, exits.size());
        } finally { platform.log.removeHandler(handler); }
    }
    @Test public void shutdownCompletesQueuedReloadsAndCancelsPreparation() throws Exception {
        File compiler = write("fake-compiler", "#!/usr/bin/env python3\nimport time,pathlib\npathlib.Path('building').touch()\ntime.sleep(30)\n");
        assertTrue(compiler.setExecutable(true)); write("slow/main.cpp", "int main(){}\n");
        String previous = System.getProperty("tranforcpp.compiler");
        System.setProperty("tranforcpp.compiler", compiler.getAbsolutePath());
        try {
            CompletableFuture<Void> first = manager.reload();
            await(() -> new File(platform.dir,"slow/building").exists());
            CompletableFuture<Void> queued = manager.reload();
            manager.close();
            await(() -> first.isDone() && queued.isDone());
            assertFalse(manager.isRunning());
            try { manager.reload(); fail(); } catch (IllegalStateException expected) {}
        } finally { if (previous == null) System.clearProperty("tranforcpp.compiler"); else System.setProperty("tranforcpp.compiler", previous); }
    }
    @Test public void emptyDirectoriesDoNotCreateProgramsAndUnknownIdsFail() throws Exception {
        write("empty/readme.txt", "nothing to run");
        write(".hidden/main.py", "raise RuntimeError('must not run')");
        manager.reload().get(5, TimeUnit.SECONDS); assertTrue(manager.statuses().isEmpty());
        try { manager.reload("missing").get(5, TimeUnit.SECONDS); fail(); } catch (ExecutionException expected) {
            assertTrue(expected.getCause().getMessage().contains("Unknown program"));
        }
    }
    @Test public void newDirectoryCanStartWithoutReloadingOtherPrograms() throws Exception {
        python("existing", ""); manager.reload().get(10, TimeUnit.SECONDS);
        python("added", ""); manager.reload("added").get(10, TimeUnit.SECONDS);
        java.util.concurrent.atomic.AtomicInteger serializations = new java.util.concurrent.atomic.AtomicInteger();
        manager.sendEvent("PlayerJoin", new Object() {
            public String toString() { serializations.incrementAndGet(); return "both"; }
        });
        await(() -> platform.messages.size() == 2);
        assertEquals("Serialize once before fan-out", 1, serializations.get());
        assertTrue(platform.messages.contains("existing:both"));
        assertTrue(platform.messages.contains("added:both"));
    }
    @Test public void singleProgramReloadRejectsItsOldQueuedActions() throws Exception {
        python("healthy", "");
        write("reloaded/main.py", "from tranforcpp import broadcast,run\nbroadcast('startup')\nrun()\n");
        manager.reload().get(10, TimeUnit.SECONDS);
        Runnable stale = platform.tasks.poll(5, TimeUnit.SECONDS); assertNotNull(stale);
        manager.reload("reloaded").get(10, TimeUnit.SECONDS);
        stale.run(); assertTrue(platform.messages.isEmpty());
        await(() -> platform.messages.contains("startup"));
        manager.sendEvent("PlayerJoin", "still-running");
        await(() -> platform.messages.contains("healthy:still-running"));
        assertEquals(2, platform.messages.size());
    }
    static final class RecordingPlatform implements Platform {
        final File dir; final Logger log = Logger.getLogger("manager-test."+UUID.randomUUID());
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        final List<String> messages = new CopyOnWriteArrayList<>();
        RecordingPlatform(File dir) { this.dir = dir; }
        public File directory() { return dir; } public Logger logger() { return log; }
        public void execute(Runnable action) { tasks.add(action); }
        public void broadcast(String message) { messages.add(message); }
        public void sendMessage(String player, String message) { messages.add(player+":"+message); }
        public void dispatchCommand(String command) { messages.add(command); }
    }
}

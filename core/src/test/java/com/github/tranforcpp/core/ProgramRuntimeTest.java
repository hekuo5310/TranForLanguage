package com.github.tranforcpp.core;

import com.github.tranforcpp.core.language.*;
import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.Assert.*;

public class ProgramRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private FakePlatform platform;
    private ProgramRuntime runtime;
    @Before public void create() throws Exception {
        platform = new FakePlatform(temporary.newFolder("program with spaces"));
        runtime = new ProgramRuntime(platform);
    }
    @After public void stop() { runtime.close(); }
    private void write(String name, String text) throws Exception {
        Files.write(new File(platform.dir, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("Timed out waiting for program", condition.getAsBoolean());
    }
    @Test public void legacyCppCompilesAndDeliversSingleEventAndActions() throws Exception {
        write("main.cpp", "#include <iostream>\n#include <string>\nint main(){std::string s;while(std::getline(std::cin,s)){if(s.find(\"shutdown\")!=std::string::npos)return 0;std::cout<<R\"({\"action\":\"executeCommand\",\"command\":\"say hello\"})\"<<std::endl;}}\n");
        runtime.reload().get(20, TimeUnit.SECONDS);
        assertTrue(runtime.isRunning());
        runtime.sendEvent("PlayerJoin", "one event only");
        await(() -> !platform.tasks.isEmpty());
        assertNull("IO threads must not call game APIs", platform.command);
        platform.tasks.remove().run();
        assertEquals("say hello", platform.command);
        runtime.close(); assertFalse(runtime.isRunning());
    }
    @Test public void processProviderRunsPythonAndEscapesUnicodeWithoutCppCompiler() throws Exception {
        write("program.json", "{\"language\":\"process\",\"command\":[\"python3\",\"-u\",\"main.py\"]}");
        write("main.py", "import sys,json\nfor line in sys.stdin:\n e=json.loads(line)\n if e['event']=='shutdown': break\n print(json.dumps({'action':'broadcast','message':e['args'][0]}),flush=True)\n");
        runtime.reload().get(5, TimeUnit.SECONDS);
        runtime.sendEvent("test", "你好\n\"quoted\"\\path");
        await(() -> !platform.tasks.isEmpty()); platform.tasks.remove().run();
        assertEquals("你好\n\"quoted\"\\path", platform.message);
    }
    @Test public void reloadDoesNotRunOldProcessActions() throws Exception {
        write("program.json", "{\"language\":\"process\",\"command\":[\"python3\",\"-u\",\"main.py\"]}");
        write("main.py", "import sys,json\nprint(json.dumps({'action':'broadcast','message':'startup'}),flush=True)\nfor line in sys.stdin:\n if json.loads(line)['event']=='shutdown': break\n");
        runtime.reload().get(5, TimeUnit.SECONDS); await(() -> !platform.tasks.isEmpty());
        Runnable stale = platform.tasks.remove();
        runtime.reload().get(5, TimeUnit.SECONDS); stale.run();
        assertNull(platform.message);
        runtime.close();
        try { runtime.reload(); fail(); } catch (IllegalStateException expected) {}
    }
    @Test public void unknownLanguageAndInvalidCommandFailVisibly() throws Exception {
        write("program.json", "{\"language\":\"missing\"}");
        try { runtime.reload().get(5, TimeUnit.SECONDS); fail(); } catch (ExecutionException expected) {
            assertTrue(expected.getCause().getMessage().contains("Unknown language"));
        }
        write("program.json", "{\"language\":\"process\",\"command\":\"echo unsafe\"}");
        try { runtime.reload().get(5, TimeUnit.SECONDS); fail(); } catch (ExecutionException expected) {}
        assertFalse(runtime.isRunning());
    }
    @Test public void customLanguageProviderUsesSameProtocol() throws Exception {
        runtime.languages().register(new LanguageProvider() {
            public String id() { return "example"; }
            public LaunchCommand prepare(File dir, JsonObject config, Logger logger) {
                return new LaunchCommand(dir, Arrays.asList("python3", "-c", "print('{\"action\":\"console\",\"message\":\"custom\"}')"));
            }
        });
        write("program.json", "{\"language\":\"example\"}");
        runtime.reload().get(5, TimeUnit.SECONDS); await(() -> !platform.tasks.isEmpty());
        platform.tasks.remove().run();
    }
    static class FakePlatform implements Platform {
        final File dir;
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        String command, message;
        FakePlatform(File dir) { this.dir = dir; }
        public File directory() { return dir; }
        public Logger logger() { return Logger.getLogger("test"); }
        public void execute(Runnable action) { tasks.add(action); }
        public void broadcast(String text) { message = text; }
        public void sendMessage(String player, String text) { message = text; }
        public void dispatchCommand(String text) { command = text; }
    }
}

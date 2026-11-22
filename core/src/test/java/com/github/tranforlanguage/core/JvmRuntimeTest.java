package com.github.tranforlanguage.core;

import com.github.tranforlanguage.core.jvm.BytePipe;
import com.github.tranforlanguage.core.jvm.JvmEngines;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import static org.junit.Assert.*;

/**
 * Programs translated to JVM classes ahead of time and run inside the server JVM. Without a toolchain a
 * test is skipped, unless {@code -Dtranforlanguage.test.requireJvm=true} (CI) makes that a failure.
 */
public class JvmRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ProgramRuntimeTest.FakePlatform platform;
    private ProgramRuntime runtime;
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { log.add(record.getMessage()); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @Before public void create() throws Exception {
        platform = new ProgramRuntimeTest.FakePlatform(temporary.newFolder("jvm program"));
        platform.log.addHandler(capture);
        runtime = new ProgramRuntime(platform);
    }
    @After public void stop() { runtime.close(); platform.log.removeHandler(capture); }

    private void write(String name, String source) throws IOException {
        File file = new File(platform.dir, name);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));
    }
    private String broadcast(int seconds) throws Exception {
        Runnable action = platform.tasks.poll(seconds, TimeUnit.SECONDS);
        assertNotNull("Program must produce an action", action);
        action.run();
        return platform.message;
    }
    private boolean logged(String text) { for (String line : log) if (line.contains(text)) return true; return false; }

    /** Fails or skips (see class comment) unless the last start ran inside the JVM. */
    private void assumeInJvm(String language) {
        if (logged("Running translated program inside the server JVM")) return;
        String reason = "";
        for (String line : log) if (line.contains("running as a separate process")) reason = line;
        if (Boolean.getBoolean("tranforlanguage.test.requireJvm")) fail(language + " did not run inside the JVM: " + reason);
        Assume.assumeTrue(language + " toolchain for in-JVM mode unavailable: " + reason, false);
    }

    @Test public void javaGetsItsOwnStreamsEnvironmentAndExitInsideTheJvm() throws Exception {
        Assume.assumeTrue(JvmEngines.javaVersion() >= 11);
        write("Main.java", "import tranforlanguage.TranForLanguage;\n"
            + "public class Main { public static void main(String[] a) {\n"
            + "  System.err.println(\"diagnostic on the program's own stderr\");\n"
            + "  TranForLanguage.broadcast(System.getenv(\"TRANFORLANGUAGE_PROGRAM_ID\") + \"|\" + TranForLanguage.programId());\n"
            + "  TranForLanguage.on(\"PlayerJoin\", args -> new Thread(() -> System.exit(7)).start());\n"
            + "  TranForLanguage.run();\n"
            + "}}\n");
        runtime.reload().get(120, TimeUnit.SECONDS);
        assumeInJvm("Java");
        assertEquals("@local|@local", broadcast(10));
        runtime.sendEvent("PlayerJoin", "Alex");
        // System.exit from a program thread ends the program, never the server JVM.
        long deadline = System.currentTimeMillis() + 10000;
        while (runtime.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertFalse("System.exit(7) must stop the program", runtime.isRunning());
        Thread.sleep(200);
        assertTrue(log.toString(), logged("exited unexpectedly with code 7"));
        assertTrue("stderr goes to the server log", logged("diagnostic on the program's own stderr"));
    }

    @Test public void goKeepsGoroutinesRunningWhileWaitingForInput() throws Exception {
        Assume.assumeTrue(JvmEngines.javaVersion() >= 11);
        // No event is ever sent: a blocking stdin read would freeze every goroutine, including this one.
        write("main.go", "package main\nimport (\n\t\"time\"\n\tt \"tranforlanguage\"\n)\n"
            + "func main() {\n\tgo func() { time.Sleep(300 * time.Millisecond); t.Broadcast(\"tick\") }()\n\tt.Run()\n}\n");
        runtime.reload().get(300, TimeUnit.SECONDS);
        assumeInJvm("Go");
        assertEquals("tick", broadcast(10));
    }

    @Test public void cRunsInsideTheJvmAndProcessModeIsHonoured() throws Exception {
        Assume.assumeTrue(JvmEngines.javaVersion() >= 11);
        write("main.c", "int main(void){broadcast(\"from wasm\");char s[4096];while(fgets(s,sizeof(s),stdin)){if(s[0])break;}return 0;}\n");
        runtime.reload().get(120, TimeUnit.SECONDS);
        assumeInJvm("C");
        assertEquals("from wasm", broadcast(10));
        assertTrue(new File(platform.dir, ".tranforlanguage/jvm-classes").isDirectory());

        log.clear();
        write("program.json", "{\"language\":\"c\",\"runtime\":\"process\"}");
        runtime.reload().get(120, TimeUnit.SECONDS);
        assertEquals("from wasm", broadcast(10));
        assertFalse("runtime=process must start a separate process", logged("inside the server JVM"));
    }

    @Test public void rubyThatReadsItsOwnLocationStaysAProcess() throws Exception {
        Assume.assumeTrue("JRuby needs Java 21", JvmEngines.javaVersion() >= 21);
        write("main.rb", "require 'tranforlanguage'\nTranForLanguage.broadcast(File.basename(__dir__))\nTranForLanguage.run\n");
        runtime.reload().get(300, TimeUnit.SECONDS);
        assertEquals("jvm program", broadcast(30));
        assertTrue(log.toString(), logged("uses __FILE__, __dir__ or $0"));
        assertFalse(logged("inside the server JVM"));
    }

    @Test public void rubyFilesRequireEachOtherAsCompiledClasses() throws Exception {
        Assume.assumeTrue("JRuby needs Java 21", JvmEngines.javaVersion() >= 21);
        write("lib/greeting.rb", "module Greeting\n  def self.text = 'compiled helper'\nend\n");
        write("main.rb", "require 'tranforlanguage'\nrequire_relative 'lib/greeting'\nTranForLanguage.broadcast(Greeting.text)\nTranForLanguage.run\n");
        runtime.reload().get(300, TimeUnit.SECONDS);
        assumeInJvm("Ruby");
        assertEquals("compiled helper", broadcast(30));
        assertTrue(new File(platform.dir, ".tranforlanguage/ruby-classes/lib/greeting.class").isFile());
    }

    @Test public void luaRequiresCompiledModulesReadsRelativeFilesAndExits() throws Exception {
        Assume.assumeTrue(JvmEngines.javaVersion() >= 11);
        write("lib/greeting.lua", "return { text = function() return 'compiled module' end }\n");
        write("data.txt", "from the program directory\n");
        write("main.lua", "local t = require('tranforlanguage')\nlocal greeting = require('lib.greeting')\n"
            + "local file = assert(io.open('data.txt'))\nlocal line = file:read('*l')\nfile:close()\n"
            + "t.broadcast(greeting.text() .. ' / ' .. line .. ' / ' .. os.getenv('TRANFORLANGUAGE_PROGRAM_ID'))\n"
            + "t.on('PlayerJoin', function() os.exit(5) end)\nt.run()\n");
        runtime.reload().get(120, TimeUnit.SECONDS);
        assumeInJvm("Lua");
        assertEquals("compiled module / from the program directory / @local", broadcast(10));
        assertTrue(new File(platform.dir, ".tranforlanguage/lua-classes/modules.properties").isFile());
        runtime.sendEvent("PlayerJoin", "Alex");
        long deadline = System.currentTimeMillis() + 10000;
        while (runtime.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(200);
        assertTrue(log.toString(), logged("exited unexpectedly with code 5"));
    }

    @Test public void luaRunsWithTheNativeInterpreterInProcessMode() throws Exception {
        String lua = System.getProperty("tranforlanguage.lua", "lua");
        boolean available;
        try { available = new ProcessBuilder(lua, "-v").redirectErrorStream(true).start().waitFor() == 0; }
        catch (IOException missing) { available = false; }
        Assume.assumeTrue("no " + lua + " interpreter", available || Boolean.getBoolean("tranforlanguage.test.requireJvm"));
        write("program.json", "{\"runtime\":\"process\"}");
        write("main.lua", "local t = require('tranforlanguage')\nt.on('PlayerJoin', function(p) t.broadcast(p) end)\nt.run()\n");
        runtime.reload().get(60, TimeUnit.SECONDS);
        runtime.sendEvent("PlayerJoin", "你好 \"quoted\" \\path");
        assertEquals("你好 \"quoted\" \\path", broadcast(10));
        assertFalse(logged("inside the server JVM"));
    }

    @Test public void jvmRuntimeFailsForProgramsThatCanOnlyRunAsProcesses() throws Exception {
        write("program.json", "{\"language\":\"python\",\"runtime\":\"jvm\"}");
        write("main.py", "from tranforlanguage import run\nrun()\n");
        try {
            runtime.reload().get(60, TimeUnit.SECONDS);
            fail("python with runtime=jvm must not start as a process");
        } catch (ExecutionException expected) {
            assertTrue(String.valueOf(expected.getCause()), String.valueOf(expected.getCause()).contains("runtime \"jvm\" requested"));
        }
        assertFalse(runtime.isRunning());
    }

    @Test public void pipeReportsCompleteLines() throws Exception {
        BytePipe pipe = new BytePipe(16);
        assertFalse(pipe.lineReady());
        pipe.sink().write("par".getBytes(StandardCharsets.UTF_8));
        assertFalse("a partial line is not ready", pipe.lineReady());
        pipe.sink().write("t\nnext".getBytes(StandardCharsets.UTF_8));
        assertTrue(pipe.lineReady());
        byte[] buffer = new byte[5];
        assertEquals(5, pipe.source().read(buffer));
        assertFalse("the newline has been read", pipe.lineReady());
        pipe.sink().close();
        assertTrue("end of input is ready", pipe.lineReady());
        assertEquals(4, pipe.source().read(buffer));
        assertEquals(-1, pipe.source().read(buffer));
    }
}

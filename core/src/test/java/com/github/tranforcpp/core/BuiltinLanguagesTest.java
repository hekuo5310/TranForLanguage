package com.github.tranforcpp.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** These tests build and run real user programs, including the managed SDKs. */
public class BuiltinLanguagesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ProgramRuntimeTest.FakePlatform platform;
    private ProgramRuntime runtime;
    private static final String TEXT = "你好\n\"quoted\"\\path";
    @Before public void create() throws Exception {
        platform = new ProgramRuntimeTest.FakePlatform(temporary.newFolder("program with spaces"));
        runtime = new ProgramRuntime(platform);
    }
    @After public void stop() { runtime.close(); }
    private void write(String name, String source) throws IOException {
        Files.write(new File(platform.dir, name).toPath(), source.getBytes(StandardCharsets.UTF_8));
    }
    private void receive(String expected) throws Exception {
        Runnable action = platform.tasks.poll(10, TimeUnit.SECONDS);
        assertNotNull("Program must produce an action", action);
        assertNull("IO must not access game APIs", platform.message);
        action.run();
        assertEquals(expected, platform.message);
    }
    @Test public void cppCallsApiWithoutIncludeOrUserHeader() throws Exception {
        write("main.cpp", "int main(){broadcast(\"你好\\n\\\"quoted\\\"\\\\path\");std::string s;while(std::getline(std::cin,s)){if(s.find(\"shutdown\")!=std::string::npos)break;}}\n");
        runtime.reload().get(30, TimeUnit.SECONDS);
        receive(TEXT);
        assertFalse(new File(platform.dir, "tranforcpp_api.h").exists());
    }
    @Test public void cCallsApiAcrossTranslationUnitsWithoutUserHeader() throws Exception {
        write("main.c", "void hello(void);int main(void){hello();char s[4096];while(fgets(s,sizeof(s),stdin)){if(s[0])break;}return 0;}\n");
        write("hello.c", "void hello(void){broadcast(\"你好\\n\\\"quoted\\\"\\\\path\");}\n");
        runtime.reload().get(30, TimeUnit.SECONDS);
        receive(TEXT);
        assertFalse(new File(platform.dir, "tranforcpp.h").exists());
    }
    @Test public void pythonImportsBundledSdkAndReceivesUnicodeEvent() throws Exception {
        write("main.py", "from tranforcpp import on,run,broadcast\n@on('PlayerJoin')\ndef joined(player):\n broadcast(player)\nrun()\n");
        runtime.reload().get(10, TimeUnit.SECONDS);
        runtime.sendEvent("PlayerJoin", TEXT);
        receive(TEXT);
        assertFalse(new File(platform.dir, "tranforcpp.py").exists());
    }
    @Test public void rustCompilesWithBundledCrateAndReceivesUnicodeEvent() throws Exception {
        write("main.rs", "use tranforcpp::{run,broadcast};fn main(){run(|e|{if e.event==\"PlayerJoin\"{broadcast(&e.args[0]).unwrap();}}).unwrap();}\n");
        try { runtime.reload().get(310, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            File log = new File(platform.dir, "compile.log");
            throw new AssertionError(log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8)
                : "Rust did not start compiling", failure);
        }
        runtime.sendEvent("PlayerJoin", TEXT);
        receive(TEXT);
        assertFalse(new File(platform.dir, "Cargo.toml").exists());
        assertTrue(new File(platform.dir, ".tranforcpp/rust-project/Cargo.lock").isFile());
    }
    @Test public void explicitLanguageResolvesMixedSourcesAndNestedEntrypoint() throws Exception {
        write("unused.cpp", "this is not C++");
        write("main.py", "raise RuntimeError('wrong entrypoint')");
        try { runtime.reload().get(5, TimeUnit.SECONDS); fail(); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("Multiple program languages")); }
        File scripts = new File(platform.dir, "scripts"); assertTrue(scripts.mkdir());
        write("scripts/start.py", "from tranforcpp import broadcast\nbroadcast('chosen')\n");
        write("program.json", "{\"language\":\"python\",\"entrypoint\":\"scripts/start.py\"}");
        runtime.reload().get(10, TimeUnit.SECONDS);
        receive("chosen");
    }
    @Test public void missingEntrypointAndBrokenCompilerFailInsteadOfRunningOldBinary() throws Exception {
        write("program.json", "{\"language\":\"rust\"}");
        try { runtime.reload().get(5, TimeUnit.SECONDS); fail(); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("Missing program entrypoint")); }
        write("program.json", "{\"language\":\"c\"}");
        write("main.c", "int main(void){broadcast(\"ok\");char s[16];fgets(s,sizeof(s),stdin);return 0;}");
        runtime.reload().get(20, TimeUnit.SECONDS); receive("ok");
        write("main.c", "not valid C");
        try { runtime.reload().get(20, TimeUnit.SECONDS); fail(); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("Compilation failed")); }
        assertFalse(runtime.isRunning());
    }
}

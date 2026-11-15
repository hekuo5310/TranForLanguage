package com.github.tranforlanguage.core;

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
        assertFalse(new File(platform.dir, "tranforlanguage.hpp").exists());
    }
    @Test public void cCallsApiAcrossTranslationUnitsWithoutUserHeader() throws Exception {
        write("main.c", "void hello(void);int main(void){hello();char s[4096];while(fgets(s,sizeof(s),stdin)){if(s[0])break;}return 0;}\n");
        write("hello.c", "void hello(void){broadcast(\"你好\\n\\\"quoted\\\"\\\\path\");}\n");
        runtime.reload().get(30, TimeUnit.SECONDS);
        receive(TEXT);
        assertFalse(new File(platform.dir, "tranforlanguage.h").exists());
    }
    @Test public void pythonImportsBundledSdkAndReceivesUnicodeEvent() throws Exception {
        write("main.py", "from tranforlanguage import on,run,broadcast\n@on('PlayerJoin')\ndef joined(player):\n broadcast(player)\nrun()\n");
        runtime.reload().get(10, TimeUnit.SECONDS);
        runtime.sendEvent("PlayerJoin", TEXT);
        receive(TEXT);
        assertFalse(new File(platform.dir, "tranforlanguage.py").exists());
    }
    @Test public void pythonRequirementsCreateIsolatedEnvironmentAndReloadOnChanges() throws Exception {
        write("requirements.txt", "# empty dependency list\n");
        write("main.py", "import sys\nfrom tranforlanguage import broadcast,run\nbroadcast('venv' if sys.prefix != sys.base_prefix else 'global')\nrun()\n");
        runtime.reload().get(180, TimeUnit.SECONDS);
        receive("venv");
        File stamp = new File(platform.dir, ".tranforlanguage/python-venv/.tfl-requirements");
        assertTrue(stamp.isFile());
        String original = new String(Files.readAllBytes(stamp.toPath()), StandardCharsets.UTF_8);
        write("requirements.txt", "# changed dependency list\n");
        runtime.reload().get(180, TimeUnit.SECONDS);
        receive("venv");
        assertNotEquals(original, new String(Files.readAllBytes(stamp.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void javascriptPackageManifestIsInstalledWithoutModifyingSource() throws Exception {
        write("package.json", "{\"name\":\"tfl-test\",\"version\":\"1.0.0\",\"dependencies\":{}}\n");
        write("main.js", "const t=require('tranforlanguage');t.broadcast('npm');t.run();\n");
        runtime.reload().get(180, TimeUnit.SECONDS);
        receive("npm");
        assertTrue(new File(platform.dir, ".tranforlanguage/node-project/.tfl-dependencies").isFile());
        assertFalse(new File(platform.dir, "node_modules").exists());
    }
    @Test public void goUsesPluginModuleWithoutRewritingIt() throws Exception {
        String module = "module example.com/plugin\n\ngo 1.21\n";
        write("go.mod", module);
        write("main.go", "package main\nimport t \"tranforlanguage\"\nfunc main(){t.Broadcast(\"go.mod\");t.Run()}\n");
        runtime.reload().get(300, TimeUnit.SECONDS);
        receive("go.mod");
        assertEquals(module, new String(Files.readAllBytes(new File(platform.dir, "go.mod").toPath()), StandardCharsets.UTF_8));
    }
    @Test public void rustCompilesWithBundledCrateAndReceivesUnicodeEvent() throws Exception {
        write("main.rs", "use tranforlanguage::{run,broadcast};fn main(){run(|e|{if e.event==\"PlayerJoin\"{broadcast(&e.args[0]).unwrap();}}).unwrap();}\n");
        try { runtime.reload().get(310, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            File log = new File(platform.dir, "compile.log");
            throw new AssertionError(log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8)
                : "Rust did not start compiling", failure);
        }
        runtime.sendEvent("PlayerJoin", TEXT);
        receive(TEXT);
        assertFalse(new File(platform.dir, "Cargo.toml").exists());
        assertTrue(new File(platform.dir, ".tranforlanguage/rust-project/Cargo.lock").isFile());
    }
    private void roundTrip(String file, String source, int seconds) throws Exception {
        write(file, source);
        try { runtime.reload().get(seconds, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            File log = new File(platform.dir, "compile.log");
            throw new AssertionError(log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "no compile.log", failure);
        }
        runtime.sendEvent("PlayerJoin", TEXT);
        receive(TEXT);
    }
    @Test public void javascriptRequiresBundledSdkAndReceivesUnicodeEvent() throws Exception {
        roundTrip("main.js", "const t=require('tranforlanguage');t.on('PlayerJoin',p=>t.broadcast(p));t.run();\n", 10);
    }
    @Test public void goBuildsWithBundledModuleAndReceivesUnicodeEvent() throws Exception {
        roundTrip("main.go", "package main\nimport t \"tranforlanguage\"\nfunc main(){t.On(\"PlayerJoin\",func(a []string){t.Broadcast(a[0])});t.Run()}\n", 300);
        assertFalse(new File(platform.dir, "go.mod").exists());
    }
    @Test public void rubyRequiresBundledSdkAndReceivesUnicodeEvent() throws Exception {
        roundTrip("main.rb", "require 'tranforlanguage'\nTranForLanguage.on('PlayerJoin') { |p| TranForLanguage.broadcast(p) }\nTranForLanguage.run\n", 10);
    }
    @Test public void phpPreloadsSdkAndReceivesUnicodeEvent() throws Exception {
        roundTrip("main.php", "<?php\nTranForLanguage::on('PlayerJoin', fn($p) => TranForLanguage::broadcast($p));\nTranForLanguage::run();\n", 10);
    }
    @Test public void javaCompilesWithBundledSdkAndReceivesUnicodeEvent() throws Exception {
        roundTrip("Main.java", "import tranforlanguage.TranForLanguage;\npublic class Main{public static void main(String[] a){TranForLanguage.on(\"PlayerJoin\",p->TranForLanguage.broadcast(p[0]));TranForLanguage.run();}}\n", 120);
    }
    @Test public void standaloneRuntimeAnswersCallsWithAnError() throws Exception {
        write("main.py", "from tranforlanguage import call,broadcast,CallError\ntry:\n call('x','y')\nexcept CallError as e:\n broadcast(str(e))\n");
        runtime.reload().get(10, TimeUnit.SECONDS);
        receive("inter-program calls require a program manager");
    }
    @Test public void explicitLanguageResolvesMixedSourcesAndNestedEntrypoint() throws Exception {
        write("unused.cpp", "this is not C++");
        write("main.py", "raise RuntimeError('wrong entrypoint')");
        try { runtime.reload().get(5, TimeUnit.SECONDS); fail(); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("Multiple program languages")); }
        File scripts = new File(platform.dir, "scripts"); assertTrue(scripts.mkdir());
        write("scripts/start.py", "from tranforlanguage import broadcast\nbroadcast('chosen')\n");
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

package com.github.tranforlanguage.core.language;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.tomlj.*;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** Regression checks for dependency arguments, caching and managed Cargo manifests. */
public class DependencyManagementTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File write(File dir, String path, String content) throws IOException {
        File file = new File(dir, path);
        Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test public void pkgConfigFlagsKeepQuotedAndEscapedSpaces() throws Exception {
        assertEquals(Arrays.asList("-I/path with space", "-L/lib with spaces",
            "-DFLAG=some value", "-lfoo"),
            ProgramFiles.splitArguments("-I/path\\ with\\ space '-L/lib with spaces' \"-DFLAG=some value\" -lfoo"));
        try { ProgramFiles.splitArguments("-Ibroken\\"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unterminated")); }
    }

    @Test public void capturedToolCannotBlockPastDeadlineWithOpenPipe() throws Exception {
        File dir = temp.newFolder();
        long begin = System.nanoTime();
        try {
            ProgramFiles.capture(dir, Arrays.asList("python3", "-c",
                "import subprocess,sys;subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)'],stdout=sys.stdout)"),
                1, 65536);
            fail("An inherited output pipe must not hang reload");
        } catch (IOException expected) {
            assertTrue(expected.toString(), expected.toString().contains("timed out"));
        }
        assertTrue("Capture should finish close to timeout",
            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begin) < 6);
    }

    @Test public void cargoUsesStructuredTablesWithoutDuplicateDependencies() throws Exception {
        File dir = temp.newFolder();
        File original = write(dir, "Cargo.toml",
            "[package]\nname='plugin'\nversion='0.1.0'\nedition='2021'\n"
            + "# [dependencies] is a comment, not a section\n"
            + "[ dependencies ]\n"
            + "tranforlanguage = { version='0.1', features=['rpc'] }\n"
            + "helper = { path='./helper', version='0.1.0' }\n"
            + "[target.'cfg(unix)'.dependencies]\n"
            + "targethelper = { path='./native' }\n");
        String output = CargoManifest.managed(original, new File(dir,"sdk"), new File(dir,"main.rs"));
        TomlParseResult managed = Toml.parse(output);
        assertFalse(managed.errors().toString(), managed.hasErrors());
        MutableTomlTable dependencies = managed.getTable("dependencies");
        assertEquals(new File(dir, "sdk").getAbsolutePath(),
            dependencies.getTable("tranforlanguage").getString("path"));
        assertEquals("rpc", dependencies.getTable("tranforlanguage").getArray("features").getString(0));
        assertEquals(new File(dir, "helper").getCanonicalPath(), dependencies.getTable("helper").getString("path"));
        assertEquals(new File(dir, "native").getCanonicalPath(),
            managed.getTable("target").getTable(Collections.singletonList("cfg(unix)"))
                .getTable("dependencies").getTable("targethelper").getString("path"));
        assertNotNull(managed.getTable("workspace"));
        assertTrue(output.contains("# [dependencies]"));
        assertEquals(1, count(output,"tranforlanguage ="));
    }

    @Test public void cargoCopiesExternalWorkspaceDependencies() throws Exception {
        File root = temp.newFolder();
        write(root, "Cargo.toml", "[workspace]\nmembers=['plugin']\n[workspace.package]\nversion='0.1.0'\n[workspace.dependencies]\nhelper={path='./shared'}\n");
        File child = new File(root, "plugin");
        assertTrue(child.mkdir());
        File original = write(child, "Cargo.toml",
            "[package]\nname='plugin'\nversion.workspace=true\nedition='2021'\n[dependencies]\nhelper.workspace=true\n");
        String output = CargoManifest.managed(original, new File(root,"sdk"), new File(child,"main.rs"));
        TomlParseResult managed = Toml.parse(output);
        assertFalse(managed.errors().toString(), managed.hasErrors());
        assertEquals(new File(root, "shared").getCanonicalPath(),
            managed.getTable("workspace").getTable("dependencies").getTable("helper").getString("path"));
        assertFalse(managed.getTable("workspace").contains("members"));
    }

    @Test public void removedPomDoesNotReusePreviousMavenJars() throws Exception {
        File dir = temp.newFolder();
        write(dir, "Main.java", "import tranforlanguage.TranForLanguage; public class Main {public static void main(String[]a){TranForLanguage.run();}}\n");
        File stale = write(dir, ".tranforlanguage/java-deps/old.jar", "old");
        LaunchCommand command = new JavaLanguageProvider().prepare(dir, new JsonObject(), Logger.getAnonymousLogger());
        assertFalse(command.arguments().toString().contains(stale.getAbsolutePath()));
        assertFalse(command.arguments().toString().contains("java-deps"));
    }

    @Test public void rubyChecksCachedGemsAndFingerprintsGeneratedLock() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        File dir = temp.newFolder();
        write(dir, "Gemfile", "source 'https://example.invalid'\n");
        write(dir, "main.rb", "puts('unused')\n");
        File bundle = write(dir, "fake-bundle", "#!/usr/bin/env python3\n"
            + "import os,sys,pathlib\n"
            + "p=pathlib.Path(os.environ['BUNDLE_PATH']); root=pathlib.Path(os.environ['BUNDLE_GEMFILE']).parent\n"
            + "if sys.argv[1]=='check': sys.exit(0 if (p/'.ready').exists() else 1)\n"
            + "if sys.argv[1]=='install':\n"
            + " p.mkdir(parents=True,exist_ok=True); (p/'.ready').touch()\n"
            + " (root/'Gemfile.lock').write_text('GEM\\n')\n"
            + " with (root/'ruby-installs').open('a') as out: out.write('1\\n')\n");
        assertTrue(bundle.setExecutable(true));
        String before = System.getProperty("tranforlanguage.bundle");
        System.setProperty("tranforlanguage.bundle", bundle.getAbsolutePath());
        try {
            RubyLanguageProvider provider = new RubyLanguageProvider();
            provider.prepare(dir, new JsonObject(), Logger.getAnonymousLogger());
            assertTrue(new File(dir, "Gemfile.lock").isFile());
            provider.prepare(dir, new JsonObject(), Logger.getAnonymousLogger());
            assertEquals(1, count(new String(Files.readAllBytes(new File(dir, "ruby-installs").toPath()), StandardCharsets.UTF_8), "1\n"));
            Files.delete(new File(dir, ".tranforlanguage/ruby-gems/.ready").toPath());
            provider.prepare(dir, new JsonObject(), Logger.getAnonymousLogger());
            assertEquals(2, count(new String(Files.readAllBytes(new File(dir, "ruby-installs").toPath()), StandardCharsets.UTF_8), "1\n"));
        } finally {
            if (before == null) System.clearProperty("tranforlanguage.bundle");
            else System.setProperty("tranforlanguage.bundle", before);
        }
    }

    @Test public void composerChecksAutoloadAndFingerprintsGeneratedLock() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        File dir = temp.newFolder();
        write(dir, "composer.json", "{\"name\":\"tfl/plugin\",\"config\":{\"vendor-dir\":\"packages\"}}\n");
        write(dir, "main.php", "<?php\n");
        File composer = write(dir, "fake-composer", "#!/usr/bin/env python3\n"
            + "import pathlib,sys\n"
            + "root=pathlib.Path.cwd(); vendor=root/'packages'; vendor.mkdir(exist_ok=True)\n"
            + "(vendor/'autoload.php').write_text('<?php')\n"
            + "(root/'composer.lock').write_text('{\"packages\":[]}')\n"
            + "with (root/'php-installs').open('a') as out: out.write('1\\n')\n");
        assertTrue(composer.setExecutable(true));
        String before = System.getProperty("tranforlanguage.composer");
        System.setProperty("tranforlanguage.composer", composer.getAbsolutePath());
        try {
            PhpLanguageProvider provider = new PhpLanguageProvider();
            provider.prepare(dir, new JsonObject(), Logger.getAnonymousLogger());
            provider.prepare(dir, new JsonObject(), Logger.getAnonymousLogger());
            assertEquals(1, count(new String(Files.readAllBytes(new File(dir, "php-installs").toPath()), StandardCharsets.UTF_8), "1\n"));
            Files.delete(new File(dir, "packages/autoload.php").toPath());
            provider.prepare(dir, new JsonObject(), Logger.getAnonymousLogger());
            assertEquals(2, count(new String(Files.readAllBytes(new File(dir, "php-installs").toPath()), StandardCharsets.UTF_8), "1\n"));
        } finally {
            if (before == null) System.clearProperty("tranforlanguage.composer");
            else System.setProperty("tranforlanguage.composer", before);
        }
    }

    @Test public void javaRemovesInstallMarkerBeforeFailingReplacement() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        File dir = temp.newFolder();
        write(dir, "Main.java", "import tranforlanguage.TranForLanguage; public class Main {public static void main(String[]a){TranForLanguage.run();}}\n");
        write(dir, "pom.xml", "<project>first</project>");
        File mvn = write(dir, "fake-maven", "#!/usr/bin/env python3\n"
            + "import pathlib,sys\n"
            + "if pathlib.Path('fail').exists(): sys.exit(1)\n");
        assertTrue(mvn.setExecutable(true));
        String before = System.getProperty("tranforlanguage.maven");
        System.setProperty("tranforlanguage.maven", mvn.getAbsolutePath());
        try {
            JavaLanguageProvider provider = new JavaLanguageProvider();
            provider.prepare(dir, new JsonObject(), Logger.getAnonymousLogger());
            File stamp = new File(dir, ".tranforlanguage/java-dependencies");
            assertTrue(stamp.isFile());
            write(dir, "pom.xml", "<project>changed</project>");
            write(dir, "fail", "");
            try { provider.prepare(dir, new JsonObject(), Logger.getAnonymousLogger()); fail(); }
            catch (IOException expected) { assertFalse(stamp.isFile()); }
        } finally {
            if (before == null) System.clearProperty("tranforlanguage.maven");
            else System.setProperty("tranforlanguage.maven", before);
        }
    }

    private static int count(String text, String needle) {
        int n = 0, at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) { n++; at += needle.length(); }
        return n;
    }
}

package com.github.tranforlanguage.core.language;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ProgramFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void managedDependenciesAreReusedUntilManifestChanges() throws Exception {
        File root = temporary.newFolder("program");
        File manifest = new File(root, "requirements.txt");
        File lock = new File(root, "requirements.lock");
        File cache = new File(root, ".tranforlanguage/env");
        Files.write(manifest.toPath(), "one".getBytes(StandardCharsets.UTF_8));
        AtomicInteger installs = new AtomicInteger();
        ProgramFiles.Setup install = () -> {
            installs.incrementAndGet();
            Files.write(new File(cache, "installed").toPath(), "ok".getBytes(StandardCharsets.UTF_8));
        };
        ProgramFiles.cached(cache, "python", install, manifest, lock);
        ProgramFiles.cached(cache, "python", install, manifest, lock);
        assertEquals(1, installs.get());
        Files.write(lock.toPath(), "created".getBytes(StandardCharsets.UTF_8));
        ProgramFiles.cached(cache, "python", install, manifest, lock);
        assertEquals(2, installs.get());
        Files.write(manifest.toPath(), "two".getBytes(StandardCharsets.UTF_8));
        ProgramFiles.cached(cache, "python", install, manifest, lock);
        assertEquals(3, installs.get());
        assertTrue(new File(cache, "installed").isFile());
    }

    @Test public void failureDoesNotLeaveAValidInstallStamp() throws Exception {
        File root = temporary.newFolder("failed-install");
        File input = new File(root, "package.json");
        Files.write(input.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        File cache = new File(root, "cache");
        try {
            ProgramFiles.cached(cache, "npm", () -> { throw new IOException("failed"); }, input);
            fail("Expected install failure");
        } catch (IOException expected) { assertEquals("failed", expected.getMessage()); }
        assertFalse(new File(cache, ".dependencies-sha256").exists());
        ProgramFiles.cached(cache, "npm", () -> {
            Files.write(new File(cache, "success").toPath(), new byte[0]);
        }, input);
        assertTrue(new File(cache, ".dependencies-sha256").isFile());
    }

    @Test public void pkgConfigFlagsHandleQuotedPathsWithoutShell() throws Exception {
        assertEquals(Arrays.asList("-I/a path", "-DNAME=two words", "-lfoo"),
            ProgramFiles.flags(" -I'/a path' -DNAME=two\\ words -lfoo "));
        try { ProgramFiles.flags("-I'broken"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Malformed")); }
    }
}

package com.github.tranforlanguage.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Every SDK language runs side by side and calls every other one through the program bus. */
public class InteropTest {
    static final String[] LANGUAGES = {"c", "cpp", "python", "rust", "javascript", "go", "ruby", "php", "java"};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private ProgramManagerTest.RecordingPlatform platform;
    private ProgramManager manager;

    @Before public void setup() throws Exception {
        platform = new ProgramManagerTest.RecordingPlatform(temp.newFolder("interop programs"));
        manager = new ProgramManager(platform);
        Path fixtures = Paths.get(getClass().getResource("/interop").toURI());
        for (String language : LANGUAGES) {
            Path from = fixtures.resolve(language), to = platform.dir.toPath().resolve(language);
            Files.createDirectories(to);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(from)) {
                for (Path file : files) Files.copy(file, to.resolve(file.getFileName().toString()));
            }
        }
    }
    @After public void close() { manager.close(); }

    private void expect(Set<String> expected) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!new HashSet<>(platform.messages).containsAll(expected) && System.nanoTime() < end) {
            Runnable action = platform.tasks.poll(20, TimeUnit.MILLISECONDS); if (action != null) action.run();
        }
        Set<String> missing = new TreeSet<>(expected); missing.removeAll(platform.messages);
        assertTrue("Missing " + missing + "; got " + platform.messages + " / " + manager.statuses(), missing.isEmpty());
        Thread.sleep(200);
        for (Runnable action; (action = platform.tasks.poll()) != null;) action.run();
        assertEquals("No unexpected or duplicate output", new TreeSet<>(expected), new TreeSet<>(platform.messages));
        assertEquals(expected.size(), platform.messages.size());
        platform.messages.clear();
    }

    @Test public void allLanguagesRunTogetherAndCallEachOther() throws Exception {
        try { manager.reload().get(600, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            StringBuilder logs = new StringBuilder();
            for (String language : LANGUAGES) {
                File log = new File(platform.dir, language + "/compile.log");
                if (log.isFile()) logs.append("\n--- ").append(language).append(" ---\n").append(new String(Files.readAllBytes(log.toPath()), "UTF-8"));
            }
            throw new AssertionError(manager.statuses() + logs.toString(), failure);
        }
        assertEquals(LANGUAGES.length, manager.statuses().size());
        for (ProgramManager.Status status : manager.statuses()) assertEquals(status.toString(), "RUNNING", status.state);
        int n = LANGUAGES.length;

        // Direct calls around a ring, all in flight at once: every language is caller and callee.
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String self = LANGUAGES[i], next = LANGUAGES[(i + 1) % n], previous = LANGUAGES[(i + n - 1) % n];
            manager.sendEvent("Ping", self, next);
            expected.add(self + "->" + next + ":" + self);
            assertNotEquals(previous, self);
        }
        expect(expected);

        // Re-entrant calls: A asks B to call back into A while A is still waiting for B.
        expected.clear();
        for (int i = 0; i < n; i++) {
            String self = LANGUAGES[i];
            manager.sendEvent("Nested", self, LANGUAGES[(i + 1) % n]);
            expected.add("nested " + self + "=" + self + ":" + self);
        }
        expect(expected);

        // Every SDK's server() helper reaches the platform on the server thread.
        expected.clear();
        for (String self : LANGUAGES) {
            manager.sendEvent("Server", self);
            expected.add("server " + self + "=pong:" + self);
        }
        expect(expected);

        // Errors reach the caller as language-native failures.
        expected.clear();
        for (String self : LANGUAGES) {
            manager.sendEvent("Missing", self);
            expected.add("missing " + self + ":program is not running: nobody");
        }
        expect(expected);

        // Publish reaches every other program, never the sender.
        for (String sender : new String[]{"python", "go", "c"}) {
            expected.clear();
            manager.sendEvent("Announce", sender);
            for (String receiver : LANGUAGES) if (!receiver.equals(sender)) expected.add("greet " + receiver + " from " + sender + ": hi");
            expect(expected);
        }

        // A stopped program fails calls immediately; the others keep calling each other.
        manager.stop("rust").get(10, TimeUnit.SECONDS);
        manager.sendEvent("Missing", "python");
        manager.sendEvent("Ping", "go", "java");
        expected.clear(); expected.add("missing python:program is not running: nobody"); expected.add("go->java:go");
        expect(expected);
        assertEquals(0, getPending());
    }

    private int getPending() throws Exception {
        java.lang.reflect.Field field = ProgramManager.class.getDeclaredField("bus"); field.setAccessible(true);
        return ((ProgramBus) field.get(manager)).pendingCalls();
    }
}

package tranforlanguage;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;

/**
 * Per-program replacement for the process-wide parts of {@link System}, used when the program runs
 * inside the server JVM. The server rewrites the program's bytecode once, after compiling it, so that
 * {@code System.in/out/err}, {@code System.exit}, {@code System.getenv} and {@code Runtime.exit/halt}
 * land here. In a separate process this class is never used.
 */
public final class ProgramSystem {
    /** Thrown by {@link #exit(int)}: ends the program's main thread with an exit code. */
    public static final class Exit extends Error {
        private static final long serialVersionUID = 1L;
        public final int code;
        public Exit(int code) { super("exit " + code, null, false, false); this.code = code; }
    }

    // Set by the server before main runs; each program has its own class loader, so these are per program.
    public static volatile InputStream in;
    public static volatile PrintStream out, err;
    public static volatile Map<String, String> environment = Collections.emptyMap();

    private ProgramSystem() {}

    public static void exit(int code) { throw new Exit(code); }
    public static void runtimeExit(Runtime runtime, int code) { throw new Exit(code); }
    public static void runtimeHalt(Runtime runtime, int code) { throw new Exit(code); }
    public static String getenv(String name) { return environment.get(name); }
    public static Map<String, String> getenv() { return environment; }
    public static void setIn(InputStream stream) { in = stream; }
    public static void setOut(PrintStream stream) { out = stream; }
    public static void setErr(PrintStream stream) { err = stream; }
}

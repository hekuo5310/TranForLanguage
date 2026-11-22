package com.github.tranforlanguage.core.jvm;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * A program whose code was translated to JVM classes ahead of time and runs inside the server JVM.
 * It speaks the same JSON-lines protocol as a separate process, over in-memory streams.
 */
public interface JvmProgram {
    /** Runs the program to completion on the calling thread and returns its exit code. */
    int run(InputStream stdin, OutputStream stdout, OutputStream stderr, Map<String, String> environment) throws Exception;
    /** Asks a running program to stop; the streams are closed right after. */
    default void cancel() {}
    /**
     * A thread the program started ended with {@code failure}. Return true when handled; otherwise the
     * stack trace goes to the program's stderr, as an uncaught exception would in its own process.
     */
    default boolean uncaught(Thread thread, Throwable failure) { return false; }
}

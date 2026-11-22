package com.github.tranforlanguage.core.jvm;

import java.io.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Process} whose program runs on threads of the server JVM. The runtime drives it exactly
 * like an operating-system process: protocol lines go through stdin/stdout, diagnostics through stderr.
 */
public final class InJvmProcess extends Process {
    /** Exit code reported when a program had to be abandoned because it ignored the stop request. */
    public static final int ABANDONED = 137;
    private static final int PIPE_CAPACITY = 1 << 20;
    private final BytePipe stdin = new BytePipe(PIPE_CAPACITY), stdout = new BytePipe(PIPE_CAPACITY), stderr = new BytePipe(PIPE_CAPACITY);
    private final JvmProgram program;
    private final ThreadGroup group;
    private final Thread main;
    private Integer exitCode;
    private volatile boolean destroyed;

    public InJvmProcess(String name, JvmProgram program, Map<String, String> environment) {
        this.program = program;
        this.group = new ThreadGroup("TranForLanguage-" + name) {
            // Threads the program starts land in this group; their failures stay with the program.
            @Override public void uncaughtException(Thread thread, Throwable failure) {
                if (!program.uncaught(thread, failure) && !destroyed) report(failure);
            }
        };
        main = new Thread(group, () -> {
            int code;
            try { code = program.run(stdin.source(), stdout.sink(), stderr.sink(), environment); }
            catch (Throwable failure) {
                code = 1;
                if (!destroyed) report(failure);
            }
            finish(code);
        }, "TranForLanguage-" + name + "-main");
        main.setDaemon(true);
    }

    /** Starts the program thread; called once by the launcher. */
    public InJvmProcess start() { main.start(); return this; }

    private void report(Throwable failure) {
        try {
            PrintStream err = new PrintStream(stderr.sink(), true, "UTF-8");
            failure.printStackTrace(err);
            err.flush();
        } catch (IOException ignored) { /* stderr already closed */ }
    }

    private synchronized void finish(int code) {
        if (exitCode != null) return;
        exitCode = code;
        try { stdout.sink().close(); stderr.sink().close(); } catch (IOException ignored) { }
        stdin.closeAll();
        notifyAll();
    }

    @Override public OutputStream getOutputStream() { return stdin.sink(); }
    @Override public InputStream getInputStream() { return stdout.source(); }
    @Override public InputStream getErrorStream() { return stderr.source(); }

    @Override public synchronized int waitFor() throws InterruptedException {
        while (exitCode == null) wait();
        return exitCode;
    }

    @Override public synchronized boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (exitCode == null) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return true;
    }

    @Override public synchronized int exitValue() {
        if (exitCode == null) throw new IllegalThreadStateException("Program still running");
        return exitCode;
    }

    @Override public synchronized boolean isAlive() { return exitCode == null; }

    /** Asks the program to stop: its input ends and its threads are interrupted. */
    @Override public void destroy() {
        destroyed = true;
        try { program.cancel(); } catch (RuntimeException ignored) { }
        try { stdin.sink().close(); } catch (IOException ignored) { }
        group.interrupt();
    }

    /**
     * Stops waiting for the program. Threads of the server JVM cannot be killed, so a program that
     * ignores the interrupt keeps its thread until it returns; its streams are closed so it can do nothing.
     */
    @Override public Process destroyForcibly() {
        destroy();
        stdin.closeAll(); stdout.closeAll(); stderr.closeAll();
        finish(ABANDONED);
        return this;
    }
}

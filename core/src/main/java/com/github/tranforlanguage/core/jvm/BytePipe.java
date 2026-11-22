package com.github.tranforlanguage.core.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

/**
 * A bounded in-memory byte pipe between two threads. Unlike {@link java.io.PipedInputStream} it does not
 * care which thread wrote last, and it can tell a reader whether a complete line is already buffered.
 */
public final class BytePipe {
    private final byte[] buffer;
    private int head, size, newlines;
    private boolean writerClosed, readerClosed;

    public BytePipe(int capacity) { buffer = new byte[capacity]; }

    /** The reading end; it also reports whether a whole line can be read without blocking. */
    public final class Source extends InputStream {
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            synchronized (BytePipe.this) {
                while (size == 0 && !writerClosed && !readerClosed) await();
                if (readerClosed) throw new IOException("Pipe closed");
                if (size == 0) return -1;
                int count = Math.min(length, size);
                for (int i = 0; i < count; i++) {
                    byte value = buffer[(head + i) % buffer.length];
                    if (value == '\n') newlines--;
                    target[offset + i] = value;
                }
                head = (head + count) % buffer.length; size -= count;
                BytePipe.this.notifyAll();
                return count;
            }
        }
        @Override public int available() { synchronized (BytePipe.this) { return size; } }
        @Override public void close() { synchronized (BytePipe.this) { readerClosed = true; BytePipe.this.notifyAll(); } }
        public boolean lineReady() { return BytePipe.this.lineReady(); }
        public boolean awaitLine(long millis) throws InterruptedException { return BytePipe.this.awaitLine(millis); }
    }

    private final Source source = new Source();
    private final OutputStream sink = new OutputStream() {
        @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
        @Override public void write(byte[] source, int offset, int length) throws IOException {
            synchronized (BytePipe.this) {
                while (length > 0) {
                    while (size == buffer.length && !readerClosed && !writerClosed) await();
                    if (readerClosed || writerClosed) throw new IOException("Pipe closed");
                    int count = Math.min(length, buffer.length - size);
                    for (int i = 0; i < count; i++) {
                        byte value = source[offset + i];
                        if (value == '\n') newlines++;
                        buffer[(head + size + i) % buffer.length] = value;
                    }
                    size += count; offset += count; length -= count;
                    BytePipe.this.notifyAll();
                }
            }
        }
        @Override public void close() { synchronized (BytePipe.this) { writerClosed = true; BytePipe.this.notifyAll(); } }
    };

    private void await() throws InterruptedIOException {
        try { wait(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Interrupted"); }
    }

    public Source source() { return source; }
    public OutputStream sink() { return sink; }

    /** True when a reader can take a whole line, or the end of input, without blocking. */
    public synchronized boolean lineReady() { return newlines > 0 || writerClosed || readerClosed; }

    /** Waits up to {@code millis} for {@link #lineReady()}. */
    public synchronized boolean awaitLine(long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (!lineReady()) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return false;
            wait(left);
        }
        return true;
    }

    /** Closes both ends, waking any blocked reader or writer. */
    public synchronized void closeAll() { writerClosed = true; readerClosed = true; notifyAll(); }
}

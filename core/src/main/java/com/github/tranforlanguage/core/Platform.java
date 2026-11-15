package com.github.tranforlanguage.core;

import java.io.File;
import java.util.logging.Logger;

/** All game API calls are dispatched through execute on the server thread. */
public interface Platform {
    File directory();
    Logger logger();
    void execute(Runnable action);
    void broadcast(String message);
    void sendMessage(String player, String message);
    void dispatchCommand(String command);
    /**
     * Answers a call to the reserved {@code @server} target. Always invoked on the server thread.
     * Return the result text; throw {@link IllegalArgumentException} (or any exception) to fail the call.
     */
    default String serverCall(String method, java.util.List<String> args) throws Exception {
        throw new IllegalArgumentException("unknown server method: " + method);
    }
}

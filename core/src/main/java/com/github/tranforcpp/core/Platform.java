package com.github.tranforcpp.core;

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
}

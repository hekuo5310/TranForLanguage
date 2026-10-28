package com.github.tranforcpp.minecraft;

import com.github.tranforcpp.core.Platform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import java.io.File;
import java.util.logging.Logger;

/** Compiled/remapped against each target, never reflects obfuscated game members. */
public final class MinecraftPlatform implements Platform {
    private final MinecraftServer server;
    private final File directory;
    private final Logger logger = Logger.getLogger("TranForLanguage");
    public MinecraftPlatform(MinecraftServer server, File directory) { this.server = server; this.directory = directory; }
    public File directory() { return directory; }
    public Logger logger() { return logger; }
    public void execute(Runnable action) { server.execute(action); }
    public void broadcast(String message) { server.getPlayerList().broadcastSystemMessage(Component.literal(message), false); }
    public void sendMessage(String name, String message) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player != null) player.sendSystemMessage(Component.literal(message));
    }
    public void dispatchCommand(String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }
}

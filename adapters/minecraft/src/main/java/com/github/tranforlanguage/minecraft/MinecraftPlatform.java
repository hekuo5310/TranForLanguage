package com.github.tranforlanguage.minecraft;

import com.github.tranforlanguage.core.Platform;
import com.github.tranforlanguage.core.ServerArgs;
import com.google.gson.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Compiled/remapped against each target, never reflects obfuscated game members. */
public final class MinecraftPlatform implements Platform {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern ID = Pattern.compile("[a-z0-9_.\\-]+(:[a-z0-9_.\\-/]+)?");
    private static final Set<String> MODES = new HashSet<>(Arrays.asList("survival", "creative", "adventure", "spectator"));
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

    /**
     * {@code @server} methods. Version-sensitive operations run the vanilla command through Brigadier,
     * whose API is stable across releases; its syntax and execution errors become error replies.
     */
    @Override public String serverCall(String method, List<String> values) throws Exception {
        ServerArgs a = new ServerArgs(method, values);
        switch (method) {
            case "players": {
                JsonArray names = new JsonArray();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) names.add(player.getName().getString());
                return GSON.toJson(names);
            }
            case "playerInfo": {
                ServerPlayer p = player(a);
                JsonObject info = new JsonObject();
                info.addProperty("name", p.getName().getString());
                info.addProperty("uuid", p.getUUID().toString());
                info.addProperty("world", p.level().dimension().toString());
                info.addProperty("x", p.getX()); info.addProperty("y", p.getY()); info.addProperty("z", p.getZ());
                info.addProperty("health", p.getHealth());
                info.addProperty("food", p.getFoodData().getFoodLevel());
                info.addProperty("level", p.experienceLevel);
                info.addProperty("gameMode", p.gameMode.getGameModeForPlayer().name());
                return GSON.toJson(info);
            }
            case "teleport": {
                String name = player(a).getName().getString();
                String target = name + " " + number(a.decimal(1, "x")) + " " + number(a.decimal(2, "y")) + " " + number(a.decimal(3, "z"));
                command(a.has(4) ? "execute in " + dimension(a.text(4, "world")) + " run tp " + target : "tp " + target);
                return "";
            }
            case "giveItem": {
                String name = player(a).getName().getString();
                int count = a.integer(2, "count", 1);
                if (count < 1) throw new IllegalArgumentException("giveItem: count must be at least 1");
                command("give " + name + " " + id(a.text(1, "item"), "item") + " " + count);
                return "";
            }
            case "clearInventory": {
                command("clear " + player(a).getName().getString());
                return "";
            }
            case "setHealth": {
                ServerPlayer p = player(a);
                double health = a.decimal(1, "health");
                if (health < 0) throw new IllegalArgumentException("setHealth: health must not be negative");
                p.setHealth((float) Math.min(health, p.getMaxHealth()));
                return "";
            }
            case "setFood": {
                player(a).getFoodData().setFoodLevel(Math.max(0, Math.min(20, a.integer(1, "food"))));
                return "";
            }
            case "setGameMode": {
                String name = player(a).getName().getString();
                String mode = a.text(1, "mode").trim().toLowerCase(Locale.ROOT);
                if (!MODES.contains(mode)) throw new IllegalArgumentException("unknown game mode: " + a.text(1, "mode"));
                command("gamemode " + mode + " " + name);
                return "";
            }
            case "sendTitle": {
                String name = player(a).getName().getString();
                // A quoted string is a valid text component in both the JSON (pre-1.21.5) and SNBT formats.
                command("title " + name + " subtitle " + GSON.toJson(a.text(2, "subtitle", "")));
                command("title " + name + " title " + GSON.toJson(a.text(1, "title")));
                return "";
            }
            case "sendActionBar": {
                command("title " + player(a).getName().getString() + " actionbar " + GSON.toJson(a.text(1, "text")));
                return "";
            }
            case "getBlock": {
                ServerLevel level = level(a.text(0, "world"));
                String block = level.getBlockState(new BlockPos(a.block(1, "x"), a.block(2, "y"), a.block(3, "z"))).getBlock().toString();
                // Block#toString is "Block{minecraft:stone}" on every supported version.
                int open = block.indexOf('{'), close = block.lastIndexOf('}');
                return open >= 0 && close > open ? block.substring(open + 1, close) : block;
            }
            case "setBlock": {
                String world = dimension(a.text(0, "world"));
                command("execute in " + world + " run setblock " + a.block(1, "x") + " " + a.block(2, "y") + " " + a.block(3, "z")
                    + " " + id(a.text(4, "block"), "block"));
                return "";
            }
            case "kick": {
                String reason = a.text(1, "reason", "Kicked by server").replace('\n', ' ').replace('\r', ' ');
                command("kick " + player(a).getName().getString() + " " + reason);
                return "";
            }
            default: throw new IllegalArgumentException("unknown server method: " + method);
        }
    }

    private ServerPlayer player(ServerArgs a) {
        String name = a.text(0, "player");
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player == null) throw new IllegalArgumentException("player is not online: " + name);
        return player;
    }

    /** Accepts {@code minecraft:the_nether}, {@code the_nether} or the {@code ResourceKey[...]} form events report. */
    private ServerLevel level(String world) {
        String wanted = ServerArgs.dimensionId(world);
        for (ServerLevel level : server.getAllLevels())
            if (ServerArgs.dimensionId(level.dimension().toString()).equals(wanted)) return level;
        throw new IllegalArgumentException("unknown world: " + world);
    }

    private String dimension(String world) { return ServerArgs.dimensionId(level(world).dimension().toString()); }

    private static String id(String value, String kind) {
        String id = value.trim().toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("invalid " + kind + " id: " + value);
        return id;
    }

    private static String number(double value) { return BigDecimal.valueOf(value).toPlainString(); }

    private void command(String command) throws CommandSyntaxException {
        server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withSuppressedOutput());
    }
}

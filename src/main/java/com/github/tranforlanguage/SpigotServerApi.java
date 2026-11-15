package com.github.tranforlanguage;

import com.github.tranforlanguage.core.ServerArgs;
import com.github.tranforlanguage.utils.Messages;
import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.lang.reflect.Method;
import java.util.*;

/** {@code @server} methods for Bukkit/Spigot. Runs on the server thread only. */
final class SpigotServerApi {
    private static final Gson GSON = new Gson();

    String call(String method, List<String> values) throws Exception {
        ServerArgs a = new ServerArgs(method, values);
        switch (method) {
            case "players": {
                JsonArray names = new JsonArray();
                for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
                return GSON.toJson(names);
            }
            case "playerInfo": {
                Player p = player(a);
                Location l = p.getLocation();
                JsonObject info = new JsonObject();
                info.addProperty("name", p.getName());
                info.addProperty("uuid", p.getUniqueId().toString());
                info.addProperty("world", p.getWorld().getName());
                info.addProperty("x", l.getX()); info.addProperty("y", l.getY()); info.addProperty("z", l.getZ());
                info.addProperty("health", p.getHealth());
                info.addProperty("food", p.getFoodLevel());
                info.addProperty("level", p.getLevel());
                info.addProperty("gameMode", p.getGameMode().name());
                return GSON.toJson(info);
            }
            case "teleport": {
                Player p = player(a);
                double x = a.decimal(1, "x"), y = a.decimal(2, "y"), z = a.decimal(3, "z");
                World world = a.has(4) ? world(a.text(4, "world")) : p.getWorld();
                Location current = p.getLocation();
                if (!p.teleport(new Location(world, x, y, z, current.getYaw(), current.getPitch())))
                    throw new IllegalArgumentException("teleport was cancelled");
                return "";
            }
            case "giveItem": {
                Player p = player(a);
                Material material = material(a.text(1, "item"), "item");
                int count = a.integer(2, "count", 1);
                if (count < 1) throw new IllegalArgumentException("giveItem: count must be at least 1");
                // Leftovers that do not fit are dropped at the player's feet, like /give.
                for (ItemStack left : p.getInventory().addItem(new ItemStack(material, count)).values())
                    p.getWorld().dropItem(p.getLocation(), left);
                return "";
            }
            case "clearInventory": {
                Player p = player(a);
                p.getInventory().clear();
                p.getInventory().setArmorContents(new ItemStack[4]);
                return "";
            }
            case "setHealth": {
                Player p = player(a);
                double health = a.decimal(1, "health");
                if (health < 0) throw new IllegalArgumentException("setHealth: health must not be negative");
                p.setHealth(Math.min(health, maxHealth(p)));
                return "";
            }
            case "setFood": {
                Player p = player(a);
                p.setFoodLevel(Math.max(0, Math.min(20, a.integer(1, "food"))));
                return "";
            }
            case "setGameMode": {
                Player p = player(a);
                String mode = a.text(1, "mode").trim().toUpperCase(Locale.ROOT);
                try { p.setGameMode(GameMode.valueOf(mode)); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("unknown game mode: " + a.text(1, "mode")); }
                return "";
            }
            case "sendTitle": {
                Player p = player(a);
                Method title;
                try { title = Player.class.getMethod("sendTitle", String.class, String.class); }
                catch (NoSuchMethodException e) { throw new IllegalArgumentException("titles are not supported by this server version"); }
                title.invoke(p, Messages.format(a.text(1, "title")), Messages.format(a.text(2, "subtitle", "")));
                return "";
            }
            case "sendActionBar": {
                Player p = player(a);
                sendActionBar(p, Messages.format(a.text(1, "text")));
                return "";
            }
            case "getBlock": {
                Block block = world(a.text(0, "world")).getBlockAt(a.block(1, "x"), a.block(2, "y"), a.block(3, "z"));
                return "minecraft:" + block.getType().name().toLowerCase(Locale.ROOT);
            }
            case "setBlock": {
                World world = world(a.text(0, "world"));
                Material material = material(a.text(4, "block"), "block");
                if (!material.isBlock()) throw new IllegalArgumentException("setBlock: not a block: " + a.text(4, "block"));
                world.getBlockAt(a.block(1, "x"), a.block(2, "y"), a.block(3, "z")).setType(material);
                return "";
            }
            case "kick": {
                Player p = player(a);
                p.kickPlayer(Messages.format(a.text(1, "reason", "Kicked by server")));
                return "";
            }
            default: throw new IllegalArgumentException("unknown server method: " + method);
        }
    }

    private static Player player(ServerArgs a) {
        String name = a.text(0, "player");
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) throw new IllegalArgumentException("player is not online: " + name);
        return player;
    }

    private static World world(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) throw new IllegalArgumentException("unknown world: " + name);
        return world;
    }

    private static Material material(String id, String kind) {
        String path = ServerArgs.vanillaPath(id);
        Material material = path.contains(":") ? null : Material.matchMaterial(path);
        if (material == null) throw new IllegalArgumentException("unknown " + kind + ": " + id);
        return material;
    }

    @SuppressWarnings("deprecation")
    private static double maxHealth(Player player) { return player.getMaxHealth(); }

    /** Spigot 1.9+ action bar through the bundled BungeeCord chat API, reached reflectively. */
    private static void sendActionBar(Player player, String text) throws Exception {
        try {
            Class<?> type = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Class<?> component = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Object components = Class.forName("net.md_5.bungee.api.chat.TextComponent").getMethod("fromLegacyText", String.class).invoke(null, text);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object actionBar = Enum.valueOf((Class) type, "ACTION_BAR");
            Object spigot = player.getClass().getMethod("spigot").invoke(player);
            Method send = spigot.getClass().getMethod("sendMessage", type, java.lang.reflect.Array.newInstance(component, 0).getClass());
            send.setAccessible(true);
            send.invoke(spigot, actionBar, components);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalArgumentException("action bars are not supported by this server version");
        }
    }
}

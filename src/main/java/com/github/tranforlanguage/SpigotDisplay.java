package com.github.tranforlanguage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Player display APIs that older Spigot versions only expose through server packets.
 * Modern versions use the Bukkit methods; 1.8 and 1.8.8 fall back to the versioned NMS classes.
 */
final class SpigotDisplay {
    private static final Gson GSON = new Gson();

    private SpigotDisplay() {}

    static void sendTitle(Player player, String title, String subtitle) {
        try {
            Player.class.getMethod("sendTitle", String.class, String.class).invoke(player, title, subtitle);
            return;
        } catch (NoSuchMethodException missing) {
            // Titles exist in the game but not in the 1.8 Bukkit API.
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("sendTitle failed: " + failure.getMessage(), failure);
        }
        try {
            Class<?> component = nmsClass("IChatBaseComponent");
            Class<?> action = nmsClass("PacketPlayOutTitle$EnumTitleAction");
            Constructor<?> packet = nmsClass("PacketPlayOutTitle").getConstructor(action, component);
            send(player, packet.newInstance(enumConstant(action, "TITLE"), component(title)));
            if (subtitle != null && !subtitle.isEmpty())
                send(player, packet.newInstance(enumConstant(action, "SUBTITLE"), component(subtitle)));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("titles are not supported by this server version", failure);
        }
    }

    static void sendActionBar(Player player, String text) {
        try {
            Player.class.getMethod("sendActionBar", String.class).invoke(player, text);
            return;
        } catch (NoSuchMethodException missing) {
            // Paper added a direct method; Spigot uses Bungee Chat or packets below.
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("sendActionBar failed: " + failure.getMessage(), failure);
        }
        try {
            Class<?> type = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Class<?> component = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Object components = Class.forName("net.md_5.bungee.api.chat.TextComponent")
                .getMethod("fromLegacyText", String.class).invoke(null, text);
            Object actionBar = enumConstant(type, "ACTION_BAR");
            Object spigot = player.getClass().getMethod("spigot").invoke(player);
            Method send = spigot.getClass().getMethod("sendMessage", type, Array.newInstance(component, 0).getClass());
            send.setAccessible(true);
            send.invoke(spigot, actionBar, components);
            return;
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            // 1.8 has no Bungee Chat action bar.
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("sendActionBar failed: " + failure.getMessage(), failure);
        }
        try {
            Class<?> component = nmsClass("IChatBaseComponent");
            Object packet = nmsClass("PacketPlayOutChat")
                .getConstructor(component, byte.class).newInstance(component(text), (byte) 2);
            send(player, packet);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("action bars are not supported by this server version", failure);
        }
    }

    static void setPlayerListHeaderFooter(Player player, String header, String footer) {
        try {
            Player.class.getMethod("setPlayerListHeaderFooter", String.class, String.class).invoke(player, header, footer);
            return;
        } catch (NoSuchMethodException missing) {
            // Added to the Bukkit API in 1.13; older servers need the packet.
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("setPlayerListHeaderFooter failed: " + failure.getMessage(), failure);
        }
        try {
            Class<?> component = nmsClass("IChatBaseComponent");
            Object headerComponent = component(header);
            Object footerComponent = component(footer);
            Class<?> packetType = nmsClass("PacketPlayOutPlayerListHeaderFooter");
            Object packet;
            try {
                packet = packetType.getConstructor(component, component).newInstance(headerComponent, footerComponent);
            } catch (NoSuchMethodException single) {
                packet = packetType.getConstructor(component).newInstance(headerComponent);
                Field field;
                try { field = packetType.getDeclaredField("footer"); }
                catch (NoSuchFieldException renamed) {
                    // 1.8 names both components a/b; the footer is the second one.
                    List<Field> candidates = new ArrayList<>();
                    for (Field candidate : packetType.getDeclaredFields())
                        if (candidate.getType().isAssignableFrom(component)) candidates.add(candidate);
                    if (candidates.size() < 2) throw renamed;
                    field = candidates.get(1);
                }
                field.setAccessible(true);
                field.set(packet, footerComponent);
            }
            send(player, packet);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("tab list headers are not supported by this server version", failure);
        }
    }

    static int getPing(Player player) {
        try {
            return ((Number) Player.class.getMethod("getPing").invoke(player)).intValue();
        } catch (NoSuchMethodException missing) {
            // Player#getPing was added in 1.16; older servers keep it on EntityPlayer.
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("getPing failed: " + failure.getMessage(), failure);
        }
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return ((Number) handle.getClass().getField("ping").get(handle)).intValue();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("ping is not supported by this server version", failure);
        }
    }

    private static Object component(String text) throws ReflectiveOperationException {
        String value = text == null ? "" : text;
        try {
            Class<?> craft = Class.forName(craftPackage() + ".util.CraftChatMessage");
            Object[] parts = (Object[]) craft.getMethod("fromString", String.class).invoke(null, value);
            if (parts != null && parts.length > 0) return parts[0];
        } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
            // Fall through to a plain JSON component.
        }
        JsonObject json = new JsonObject();
        json.addProperty("text", value);
        return nmsClass("IChatBaseComponent$ChatSerializer").getMethod("a", String.class).invoke(null, GSON.toJson(json));
    }

    private static void send(Player player, Object packet) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object connection = handle.getClass().getField("playerConnection").get(handle);
        connection.getClass().getMethod("sendPacket", nmsClass("Packet")).invoke(connection, packet);
    }

    private static String craftPackage() {
        return Bukkit.getServer().getClass().getPackage().getName();
    }

    private static Class<?> nmsClass(String name) throws ClassNotFoundException {
        String craft = craftPackage();
        if (!craft.startsWith("org.bukkit.craftbukkit")) throw new ClassNotFoundException(name);
        return Class.forName(craft.replace("org.bukkit.craftbukkit", "net.minecraft.server") + "." + name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class<Enum>) type, name);
    }
}

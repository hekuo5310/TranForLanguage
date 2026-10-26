package com.github.tranforcpp.utils;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** MiniMessage formatting through Bukkit's portable String messaging API. */
public final class Messages {
    private Messages() {}
    public static String format(String text) {
        return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(text));
    }
}

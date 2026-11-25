package com.github.tranforlanguage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.Test;

import static org.junit.Assert.*;

/** The generic event payload is built without a running server. */
public class BukkitEventBridgeTest {
    @Test public void payloadContainsCommonFieldsAndCancellation() {
        SampleEvent event = new SampleEvent();
        event.setCancelled(true);
        JsonObject json = JsonParser.parseString(BukkitEventBridge.payload(event)).getAsJsonObject();
        assertEquals("SampleEvent", json.get("event").getAsString());
        assertTrue(json.get("cancelled").getAsBoolean());
        assertEquals("hello", json.get("message").getAsString());
        assertEquals(3, json.get("amount").getAsInt());
        String[] envelope = BukkitEventBridge.envelope(event);
        assertEquals("SampleEvent", envelope[0]);
        assertEquals(json.toString(), JsonParser.parseString(envelope[1]).toString());
    }

    public static final class SampleEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled;
        public String getMessage() { return "hello"; }
        public int getAmount() { return 3; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean value) { cancelled = value; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
}

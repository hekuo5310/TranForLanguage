package com.github.tranforcpp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.WorldLoadEvent;
import java.io.*;
import java.util.*;
import com.github.tranforcpp.core.ProgramManager;
import com.github.tranforcpp.core.Platform;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.EventExecutor;
import com.github.tranforcpp.utils.Messages;

public class ProcessManager implements Listener {
    private final TranforCPlusPlus plugin;
    private final ProgramManager runtime;
    private boolean registered;
    private static final long MOVE_INTERVAL = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
        Math.max(0, Long.getLong("tranforcpp.move-interval-ms", 100L)));
    private final com.github.tranforcpp.core.EventRateLimiter<java.util.UUID> moves = new com.github.tranforcpp.core.EventRateLimiter<>(MOVE_INTERVAL);
    public ProcessManager(TranforCPlusPlus plugin) {
        this.plugin = plugin;
        this.runtime = new ProgramManager(new Platform() {
            public File directory() { return new File(plugin.getDataFolder().getParentFile(), "C++ Plugins"); }
            public java.util.logging.Logger logger() { return plugin.getLogger(); }
            public void execute(Runnable task) {
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task);
            }
            public void broadcast(String message) { Bukkit.broadcastMessage(Messages.format(message)); }
            public void sendMessage(String name, String message) {
                Player player = Bukkit.getPlayerExact(name);
                if (player != null) player.sendMessage(Messages.format(message));
            }
            public void dispatchCommand(String command) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command); }
        });
    }
    public void start() {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            optional("org.bukkit.event.entity.EntitySpawnEvent", event ->
                sendEvent("EntitySpawn", ((org.bukkit.event.entity.EntityEvent) event).getEntityType().name()));
            // 1.12 introduced entity pickup; older servers use PlayerPickupItemEvent.
            boolean modern = optional("org.bukkit.event.entity.EntityPickupItemEvent", event -> {
                try {
                    Object entity = event.getClass().getMethod("getEntity").invoke(event);
                    Object item = event.getClass().getMethod("getItem").invoke(event);
                    if (entity instanceof Player) sendEvent("PlayerPickupItem", ((Player) entity).getName(),
                        ((org.bukkit.entity.Item) item).getItemStack().getType().name());
                } catch (ReflectiveOperationException e) { plugin.getLogger().warning(e.toString()); }
            });
            if (!modern) optional("org.bukkit.event.player.PlayerPickupItemEvent", event -> {
                try { sendEvent("PlayerPickupItem", ((org.bukkit.event.player.PlayerEvent) event).getPlayer().getName(),
                    ((org.bukkit.entity.Item) event.getClass().getMethod("getItem").invoke(event)).getItemStack().getType().name());
                } catch (ReflectiveOperationException e) { plugin.getLogger().warning(e.toString()); }
            });
            registered = true;
        }
        runtime.reload().whenComplete((v, error) -> { if (error != null) plugin.getLogger().severe("程序启动失败: " + error); });
    }
    @SuppressWarnings("unchecked")
    private boolean optional(String name, java.util.function.Consumer<Event> handler) {
        try {
            Class<? extends Event> type = Class.forName(name).asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR,
                (listener, event) -> { if (type.isInstance(event)) handler.accept(event); }, plugin, true);
            return true;
        } catch (ClassNotFoundException e) { return false; }
    }
    public void stop() { HandlerList.unregisterAll(this); registered = false; moves.clear(); runtime.close(); }
    public ProgramManager programs() { return runtime; }
    public java.util.concurrent.CompletableFuture<Void> reloadAsync() { return runtime.reload(); }
    public void restart() { reloadAsync(); }
    public void sendEvent(String name, Object... args) {
        runtime.sendEvent(name, args);
        if (GenericTranforCEvent.getHandlerList().getRegisteredListeners().length == 0) return;
        Runnable notify = () -> Bukkit.getPluginManager().callEvent(new GenericTranforCEvent(name, args));
        if (Bukkit.isPrimaryThread()) notify.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, notify);
    }
    public static class GenericTranforCEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final String eventName;
        private final Object[] args;
        public GenericTranforCEvent(String eventName, Object... args) { this.eventName = eventName; this.args = args.clone(); }
        public String getEventName() { return eventName; }
        public Object getArg(int index) { return index >= 0 && index < args.length ? args[index] : null; }
        public int getArgCount() { return args.length; }
        public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) { sendEvent("PlayerChat", event.getPlayer().getName(), event.getMessage()); }
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        sendEvent("PlayerJoin", event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        moves.forget(event.getPlayer().getUniqueId());
        sendEvent("PlayerQuit", event.getPlayer().getName());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        sendEvent("BlockBreak", event.getPlayer().getName(), event.getBlock().getType().name());
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        sendEvent("BlockPlace", event.getPlayer().getName(), event.getBlock().getType().name());
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        sendEvent("EntityDamage", event.getEntity().getName(), String.valueOf(event.getDamage()));
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        sendEvent("EntityDeath", event.getEntity().getName());
    }

    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        String deathMsg = "Player died";
        if (event.getDeathMessage() != null) {
            deathMsg = String.valueOf(event.getDeathMessage());
        }
        sendEvent("PlayerDeath", event.getEntity().getName(), deathMsg);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        sendEvent("InventoryClick", event.getWhoClicked().getName(), String.valueOf(event.getSlot()), event.getCurrentItem() != null ? event.getCurrentItem().getType().name() : "AIR");
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        sendEvent("InventoryOpen", event.getPlayer().getName());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        sendEvent("InventoryClose", event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        org.bukkit.Location to = event.getTo(), from = event.getFrom();
        if (to == null || (from.getWorld() == to.getWorld() && from.getX() == to.getX()
            && from.getY() == to.getY() && from.getZ() == to.getZ())) return;
        long now = System.nanoTime();
        java.util.UUID id = event.getPlayer().getUniqueId();
        if (!moves.allow(id, now)) return;
        sendEvent("PlayerMove", event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        sendEvent("PlayerRespawn", event.getPlayer().getName());
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        String playerName = event.getPlayer() != null ? event.getPlayer().getName() : "null";
        sendEvent("BlockIgnite", playerName, event.getBlock().getType().name());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        String itemName = event.getItem() != null ? event.getItem().getType().name() : "null";
        sendEvent("PlayerInteract", event.getPlayer().getName(), event.getAction().name(), itemName);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        sendEvent("PlayerDropItem", event.getPlayer().getName(), event.getItemDrop().getItemStack().getType().name());
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        sendEvent("ServerCommand", event.getSender().getName(), event.getCommand());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        sendEvent("WorldLoad", event.getWorld().getName());
    }

    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
        sendEvent("WeatherChange", event.getWorld().getName(), String.valueOf(event.toWeatherState()));
    }

    @EventHandler
    public void onHangingBreak(HangingBreakEvent event) {
        sendEvent("HangingBreak", event.getEntity().getType().name(), event.getCause().name());
    }
    
}

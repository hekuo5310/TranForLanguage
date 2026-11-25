package com.github.tranforlanguage;

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
import com.github.tranforlanguage.core.ProgramManager;
import com.github.tranforlanguage.core.Platform;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.EventExecutor;
import com.github.tranforlanguage.utils.Messages;

public class ProcessManager implements Listener {
    private final TranForLanguage plugin;
    private final ProgramManager runtime;
    private final SpigotServerApi api = new SpigotServerApi();
    private boolean registered;
    private static final long MOVE_INTERVAL = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
        Math.max(0, Long.getLong("tranforlanguage.move-interval-ms", 100L)));
    private final com.github.tranforlanguage.core.EventRateLimiter<java.util.UUID> moves = new com.github.tranforlanguage.core.EventRateLimiter<>(MOVE_INTERVAL);
    public ProcessManager(TranForLanguage plugin) {
        this.plugin = plugin;
        this.runtime = new ProgramManager(new Platform() {
            public File directory() { return new File(plugin.getDataFolder(), "programs"); }
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
            public String serverCall(String method, java.util.List<String> args) throws Exception { return api.call(method, args); }
        });
    }
    public void start() {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            optional("org.bukkit.event.entity.EntitySpawnEvent", event ->
                sendEvent("EntitySpawn", ((org.bukkit.event.entity.EntityEvent) event).getEntityType().name()));
            // VehicleCreateEvent is a separate hierarchy from EntitySpawnEvent.
            optional("org.bukkit.event.vehicle.VehicleCreateEvent", event ->
                sendEvent("EntitySpawn", ((org.bukkit.event.vehicle.VehicleEvent) event).getVehicle().getType().name()));
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
            // 1.12 introduced advancements; the API is reached reflectively to keep the 1.8 baseline.
            optional("org.bukkit.event.player.PlayerAdvancementDoneEvent", event -> {
                try {
                    Object advancement = event.getClass().getMethod("getAdvancement").invoke(event);
                    Object key = advancement.getClass().getMethod("getKey").invoke(advancement);
                    sendEvent("PlayerAdvancement", ((org.bukkit.event.player.PlayerEvent) event).getPlayer().getName(), String.valueOf(key));
                } catch (ReflectiveOperationException e) { plugin.getLogger().warning(e.toString()); }
            });
            // 1.9 / 1.13 added gliding and swimming toggles; read them reflectively like advancements.
            optional("org.bukkit.event.entity.EntityToggleGlideEvent", event -> toggle(event, "PlayerToggleGlide", "isGliding"));
            optional("org.bukkit.event.entity.EntityToggleSwimEvent", event -> toggle(event, "PlayerToggleSwim", "isSwimming"));
            // A separate opt-in stream covers every event class the running server exposes.
            int subscribed = BukkitEventBridge.register(plugin, this, this::sendBukkitEvent);
            if (subscribed > 0) plugin.getLogger().info("Registered " + subscribed + " Bukkit event classes for BukkitEvent subscribers");
            else plugin.getLogger().warning("No Bukkit event classes could be registered; BukkitEvent subscriptions will not receive events");
            registered = true;
        }
        runtime.reload().whenComplete((v, error) -> { if (error != null) plugin.getLogger().severe("程序启动失败: " + error); });
    }
    private void sendBukkitEvent(Event event) {
        if (!runtime.wantsSpigotEvents()) return;
        String[] envelope = BukkitEventBridge.envelope(event);
        runtime.sendEvent("BukkitEvent", envelope[0], envelope[1]);
    }
    private void toggle(Event event, String name, String state) {
        try {
            Object entity = event.getClass().getMethod("getEntity").invoke(event);
            if (entity instanceof Player) sendEvent(name, ((Player) entity).getName(), String.valueOf(event.getClass().getMethod(state).invoke(event)));
        } catch (ReflectiveOperationException e) { plugin.getLogger().warning(e.toString()); }
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
    public void stop() { HandlerList.unregisterAll(this); registered = false; moves.clear(); api.clear(); runtime.close(); }
    public ProgramManager programs() { return runtime; }
    public java.util.concurrent.CompletableFuture<Void> reloadAsync() { return runtime.reload(); }
    public void restart() { reloadAsync(); }
    public void sendEvent(String name, Object... args) {
        runtime.sendEvent(name, args);
        if (TranForLanguageEvent.getHandlerList().getRegisteredListeners().length == 0) return;
        Runnable notify = () -> Bukkit.getPluginManager().callEvent(new TranForLanguageEvent(name, args));
        if (Bukkit.isPrimaryThread()) notify.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, notify);
    }
    public static class TranForLanguageEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final String eventName;
        private final Object[] args;
        public TranForLanguageEvent(String eventName, Object... args) { this.eventName = eventName; this.args = args.clone(); }
        public String getEventName() { return eventName; }
        public Object getArg(int index) { return index >= 0 && index < args.length ? args[index] : null; }
        public int getArgCount() { return args.length; }
        public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) { sendEvent("PlayerChat", event.getPlayer().getName(), event.getMessage()); }
    @EventHandler
    public void onPlayerCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        String command = event.getMessage();
        sendEvent("ServerCommand", event.getPlayer().getName(), command.startsWith("/") ? command.substring(1) : command);
    }
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        sendEvent("PlayerJoin", event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        moves.forget(event.getPlayer().getUniqueId());
        api.removeBossBar(event.getPlayer());
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

    // State-change events report only changes that actually happen (cancelled attempts are skipped),
    // matching the mod loaders, which observe the resulting state once per tick.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        sendEvent("PlayerChangeWorld", event.getPlayer().getName(), event.getFrom().getName(), event.getPlayer().getWorld().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        sendEvent("PlayerGameModeChange", event.getPlayer().getName(), event.getNewGameMode().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLevelChange(PlayerLevelChangeEvent event) {
        sendEvent("PlayerLevelChange", event.getPlayer().getName(), String.valueOf(event.getOldLevel()), String.valueOf(event.getNewLevel()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        sendEvent("PlayerToggleSneak", event.getPlayer().getName(), String.valueOf(event.isSneaking()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSprint(PlayerToggleSprintEvent event) {
        sendEvent("PlayerToggleSprint", event.getPlayer().getName(), String.valueOf(event.isSprinting()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        sendEvent("PlayerToggleFlight", event.getPlayer().getName(), String.valueOf(event.isFlying()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        sendEvent("PlayerBedEnter", event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        sendEvent("PlayerBedLeave", event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThunderChange(org.bukkit.event.weather.ThunderChangeEvent event) {
        sendEvent("ThunderChange", event.getWorld().getName(), String.valueOf(event.toThunderState()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        sendEvent("PlayerKick", event.getPlayer().getName(), String.valueOf(event.getReason()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        sendEvent("PlayerItemConsume", event.getPlayer().getName(), event.getItem().getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        sendEvent("PlayerItemHeld", event.getPlayer().getName(), String.valueOf(event.getPreviousSlot()), String.valueOf(event.getNewSlot()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFoodLevelChange(org.bukkit.event.entity.FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (player.getFoodLevel() != event.getFoodLevel())
            sendEvent("PlayerFoodLevelChange", player.getName(), String.valueOf(player.getFoodLevel()), String.valueOf(event.getFoodLevel()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(org.bukkit.event.vehicle.VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player)
            sendEvent("PlayerVehicleEnter", ((Player) event.getEntered()).getName(), event.getVehicle().getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(org.bukkit.event.vehicle.VehicleExitEvent event) {
        if (event.getExited() instanceof Player)
            sendEvent("PlayerVehicleExit", ((Player) event.getExited()).getName(), event.getVehicle().getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(org.bukkit.event.inventory.CraftItemEvent event) {
        sendEvent("PlayerCraftItem", event.getWhoClicked().getName(), event.getRecipe().getResult().getType().name());
    }
}

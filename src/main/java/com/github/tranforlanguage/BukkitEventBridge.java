package com.github.tranforlanguage;

import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Optional Spigot escape hatch: every concrete Bukkit event present in the server API is
 * forwarded as one {@code BukkitEvent} protocol event with a JSON payload. Programs opt in by
 * adding {@code "BukkitEvent"} to {@code events}; the canonical events keep their stable args.
 */
final class BukkitEventBridge {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_ITEMS = 100;
    private static final int MAX_RESULT_ITEMS = 10000;
    private static final int MAX_DEPTH = 3;
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
        "player", "players", "whoClicked", "entity", "damager", "victim", "target", "owner", "attacker",
        "vehicle", "lightning", "block", "blockClicked", "blockAgainst", "from", "to", "location",
        "spawnLocation", "previousLocation", "velocity", "world", "chunk", "entityType", "type", "item",
        "itemStack", "currentItem", "cursor", "fuel", "result", "recipe", "inventory", "view", "action",
        "clickType", "hand", "animationType", "cause", "reason", "gameMode", "newGameMode", "oldGameMode",
        "status", "newLevel", "oldLevel", "level", "exp", "amount", "damage", "finalDamage", "absorption",
        "force", "duration", "slot", "newSlot", "previousSlot", "hotbarButton", "numHatches", "itemAmount",
        "itemType", "expLevelCost", "expLevelCostsOffered", "brokenItem", "egg", "bow", "projectile",
        "hitEntity", "hitBlock", "hitFace", "blocks", "affectedEntities", "changedSlots", "rawSlots", "newItems",
        "oldItems", "viewers", "transaction", "message", "command", "sender", "kickMessage", "deathMessage",
        "joinMessage", "quitMessage", "locale", "address", "playerListName", "displayName", "customName",
        "difficulty", "time", "environment", "material", "species", "blockType", "storm", "thundering",
        "asynchronous", "cancelled", "spawnReason", "creatureType", "statistic", "previousValue", "newValue",
        "damageSource", "damagerBlock", "originalDamage", "modifiers", "handItem", "armorStandItem", "bodyItem",
        "leggingsItem", "bootsItem", "newBook", "previousBookState", "newItem", "oldItem", "newExp", "oldExp",
        "newHealth", "oldHealth", "newFood", "oldFood", "valid", "sneaking", "sprinting", "flying", "gliding",
        "swimming", "sleeping", "buildable"));
    private static final Map<Class<?>, Map<String, Method>> CACHE = new ConcurrentHashMap<>();

    private BukkitEventBridge() {}

    /** Registers one monitor listener per concrete Bukkit event; returns the number registered. */
    static int register(Plugin plugin, Listener listener, java.util.function.Consumer<Event> consumer) {
        int count = 0;
        Set<HandlerList> handlers = Collections.newSetFromMap(new IdentityHashMap<HandlerList, Boolean>());
        ClassLoader loader = Bukkit.class.getClassLoader();
        if (loader == null) loader = plugin.getClass().getClassLoader();
        for (String name : eventClassNames()) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                if (!Event.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers())) continue;
                if (!claim(type, handlers)) continue;
                @SuppressWarnings("unchecked")
                Class<? extends Event> event = (Class<? extends Event>) type;
                Bukkit.getPluginManager().registerEvent(event, listener, EventPriority.MONITOR,
                    (ignored, fired) -> consumer.accept(fired), plugin, false);
                count++;
            } catch (Throwable unavailable) {
                // A class may be absent, abstract without handlers, or broken by a server patch.
            }
        }
        return count;
    }

    /** Claims the event's actual handler list; subclasses sharing it are not registered twice. */
    static boolean claim(Class<?> type, Set<HandlerList> handlers) {
        try {
            HandlerList list = (HandlerList) type.getMethod("getHandlerList").invoke(null);
            return list != null && handlers.add(list);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
    }

    /** The event class simple name as the first argument and a JSON object as the second. */
    static String[] envelope(Event event) {
        return new String[]{event.getClass().getSimpleName(), payload(event)};
    }

    static String payload(Event event) {
        JsonObject json = new JsonObject();
        json.addProperty("event", event.getClass().getSimpleName());
        for (Map.Entry<String, Method> field : fields(event.getClass()).entrySet()) {
            try {
                Object value = field.getValue().invoke(event);
                json.add(field.getKey(), convert(value, 0));
            } catch (Throwable ignored) {
                // Version-specific getters may throw when their backing object is unavailable.
            }
        }
        return GSON.toJson(json);
    }

    private static Map<String, Method> fields(Class<?> type) {
        Map<String, Method> cached = CACHE.get(type);
        if (cached != null) return cached;
        Map<String, Method> found = new LinkedHashMap<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterTypes().length != 0 || Modifier.isStatic(method.getModifiers())) continue;
            if (method.getReturnType() == void.class) continue;
            String property = property(method);
            if (property == null || !FIELDS.contains(property) || found.containsKey(property)) continue;
            try { method.setAccessible(true); } catch (RuntimeException ignored) { }
            found.put(property, method);
        }
        Map<String, Method> result = Collections.unmodifiableMap(found);
        CACHE.put(type, result);
        return result;
    }

    private static String property(Method method) {
        String name = method.getName();
        String property;
        if (name.equals("isCancelled")) property = "cancelled";
        else if (name.equals("hasStorm")) property = "storm";
        else if (name.equals("isThundering")) property = "thundering";
        else if (name.equals("isAsynchronous")) property = "asynchronous";
        else if (name.startsWith("get") && name.length() > 3) property = name.substring(3);
        else if (name.startsWith("is") && name.length() > 2 && method.getReturnType() == boolean.class) property = name.substring(2);
        else if (name.startsWith("has") && name.length() > 3 && method.getReturnType() == boolean.class) property = name.substring(3);
        else return null;
        if (property.isEmpty()) return null;
        return Character.toLowerCase(property.charAt(0)) + property.substring(1);
    }

    static JsonElement convert(Object value, int depth) {
        return convert(value, depth, MAX_ITEMS, false);
    }

    static JsonElement convertComplete(Object value, int depth) {
        return convert(value, depth, MAX_RESULT_ITEMS, true);
    }

    private static JsonElement convert(Object value, int depth, int maxItems, boolean failOnOverflow) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement) return (JsonElement) value;
        if (value instanceof String) return new JsonPrimitive((String) value);
        if (value instanceof Character) return new JsonPrimitive(value.toString());
        if (value instanceof Number) return new JsonPrimitive((Number) value);
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        if (value instanceof Enum) return new JsonPrimitive(((Enum<?>) value).name());
        if (value instanceof UUID) return new JsonPrimitive(value.toString());
        if (value instanceof World) return new JsonPrimitive(((World) value).getName());
        if (value instanceof Server) {
            JsonObject server = new JsonObject();
            Server s = (Server) value;
            server.addProperty("version", s.getVersion());
            server.addProperty("bukkitVersion", s.getBukkitVersion());
            server.addProperty("online", s.getOnlinePlayers().size());
            server.addProperty("maxPlayers", s.getMaxPlayers());
            return server;
        }
        if (value instanceof Player) {
            JsonObject player = new JsonObject();
            Player p = (Player) value;
            player.addProperty("name", p.getName());
            player.addProperty("uuid", p.getUniqueId().toString());
            return player;
        }
        if (value instanceof Entity) {
            JsonObject entity = new JsonObject();
            Entity e = (Entity) value;
            entity.addProperty("uuid", e.getUniqueId().toString());
            entity.addProperty("type", e.getType().name());
            entity.addProperty("name", e.getName());
            return entity;
        }
        if (value instanceof OfflinePlayer) {
            JsonObject player = new JsonObject();
            OfflinePlayer p = (OfflinePlayer) value;
            player.addProperty("name", p.getName());
            player.addProperty("uuid", p.getUniqueId().toString());
            player.addProperty("online", p.isOnline());
            return player;
        }
        if (value instanceof CommandSender) return new JsonPrimitive(((CommandSender) value).getName());
        if (value instanceof Block) {
            JsonObject block = new JsonObject();
            Block b = (Block) value;
            block.addProperty("type", b.getType().name());
            block.addProperty("world", b.getWorld().getName());
            block.addProperty("x", b.getX());
            block.addProperty("y", b.getY());
            block.addProperty("z", b.getZ());
            return block;
        }
        if (value instanceof BlockState) {
            JsonObject state = new JsonObject();
            BlockState b = (BlockState) value;
            state.addProperty("type", b.getType().name());
            Location location = b.getLocation();
            state.addProperty("world", location.getWorld() == null ? null : location.getWorld().getName());
            state.addProperty("x", location.getX());
            state.addProperty("y", location.getY());
            state.addProperty("z", location.getZ());
            return state;
        }
        if (value instanceof Location) {
            JsonObject location = new JsonObject();
            Location l = (Location) value;
            location.addProperty("world", l.getWorld() == null ? null : l.getWorld().getName());
            location.addProperty("x", l.getX());
            location.addProperty("y", l.getY());
            location.addProperty("z", l.getZ());
            location.addProperty("yaw", l.getYaw());
            location.addProperty("pitch", l.getPitch());
            return location;
        }
        if (value instanceof ItemStack) {
            JsonObject item = new JsonObject();
            ItemStack stack = (ItemStack) value;
            item.addProperty("type", stack.getType().name());
            item.addProperty("amount", stack.getAmount());
            item.addProperty("durability", stack.getDurability());
            return item;
        }
        if (value instanceof Inventory) {
            JsonObject inventory = new JsonObject();
            Inventory inv = (Inventory) value;
            inventory.addProperty("type", inv.getType().name());
            inventory.addProperty("size", inv.getSize());
            return inventory;
        }
        if (value instanceof InventoryView) {
            JsonObject view = new JsonObject();
            InventoryView inv = (InventoryView) value;
            view.addProperty("type", inv.getType().name());
            view.addProperty("top", inv.getTopInventory().getType().name());
            return view;
        }
        if (value instanceof Objective) {
            JsonObject objective = new JsonObject();
            Objective o = (Objective) value;
            objective.addProperty("name", o.getName());
            objective.addProperty("displayName", o.getDisplayName());
            objective.addProperty("criteria", o.getCriteria());
            try {
                objective.addProperty("displaySlot", o.getDisplaySlot() == null ? null : o.getDisplaySlot().name());
            } catch (IllegalStateException unregistered) {
                objective.add("displaySlot", JsonNull.INSTANCE);
            }
            return objective;
        }
        if (value instanceof Team) {
            JsonObject team = new JsonObject();
            Team t = (Team) value;
            team.addProperty("name", t.getName());
            team.addProperty("displayName", t.getDisplayName());
            team.addProperty("size", t.getSize());
            JsonArray entries = new JsonArray();
            int count = 0;
            for (String entry : t.getEntries()) {
                if (count >= maxItems) {
                    if (failOnOverflow) throw new IllegalArgumentException("result exceeds " + maxItems + " items");
                    break;
                }
                entries.add(entry);
                count++;
            }
            team.add("entries", entries);
            return team;
        }
        if (value instanceof Score) {
            JsonObject score = new JsonObject();
            Score s = (Score) value;
            score.addProperty("entry", s.getEntry());
            score.addProperty("score", s.getScore());
            score.addProperty("objective", s.getObjective().getName());
            return score;
        }
        if (value instanceof MapView) {
            JsonObject map = new JsonObject();
            MapView view = (MapView) value;
            map.addProperty("id", view.getId());
            map.addProperty("world", view.getWorld() == null ? null : view.getWorld().getName());
            map.addProperty("centerX", view.getCenterX());
            map.addProperty("centerZ", view.getCenterZ());
            map.addProperty("scale", view.getScale().name());
            map.addProperty("virtual", view.isVirtual());
            return map;
        }
        if (value instanceof WorldBorder) {
            JsonObject border = new JsonObject();
            WorldBorder b = (WorldBorder) value;
            border.addProperty("size", b.getSize());
            Location center = b.getCenter();
            border.addProperty("centerX", center.getX());
            border.addProperty("centerZ", center.getZ());
            border.addProperty("warningDistance", b.getWarningDistance());
            border.addProperty("damageAmount", b.getDamageAmount());
            return border;
        }
        if (value instanceof Chunk) {
            JsonObject chunk = new JsonObject();
            Chunk c = (Chunk) value;
            chunk.addProperty("world", c.getWorld().getName());
            chunk.addProperty("x", c.getX());
            chunk.addProperty("z", c.getZ());
            return chunk;
        }
        if (value instanceof Vector) {
            JsonObject vector = new JsonObject();
            Vector v = (Vector) value;
            vector.addProperty("x", v.getX());
            vector.addProperty("y", v.getY());
            vector.addProperty("z", v.getZ());
            return vector;
        }
        if (value instanceof InetSocketAddress) return new JsonPrimitive(((InetSocketAddress) value).getHostString());
        if (depth < MAX_DEPTH && value instanceof Map) {
            JsonObject map = new JsonObject();
            int count = 0;
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                if (count >= maxItems) {
                    if (failOnOverflow) throw new IllegalArgumentException("result exceeds " + maxItems + " items");
                    break;
                }
                map.add(String.valueOf(item.getKey()), convert(item.getValue(), depth + 1, maxItems, failOnOverflow));
                count++;
            }
            return map;
        }
        if (depth < MAX_DEPTH && value instanceof Iterable) {
            JsonArray array = new JsonArray();
            int count = 0;
            for (Object item : (Iterable<?>) value) {
                if (count >= maxItems) {
                    if (failOnOverflow) throw new IllegalArgumentException("result exceeds " + maxItems + " items");
                    break;
                }
                array.add(convert(item, depth + 1, maxItems, failOnOverflow));
                count++;
            }
            return array;
        }
        if (depth < MAX_DEPTH && value.getClass().isArray()) {
            int total = Array.getLength(value);
            if (failOnOverflow && total > maxItems) throw new IllegalArgumentException("result exceeds " + maxItems + " items");
            JsonArray array = new JsonArray();
            int length = Math.min(total, maxItems);
            for (int i = 0; i < length; i++) array.add(convert(Array.get(value, i), depth + 1, maxItems, failOnOverflow));
            return array;
        }
        String text = value.toString();
        return new JsonPrimitive(text.contains("@") ? value.getClass().getSimpleName() : text);
    }

    /** Event classes visible in the server API; falls back to the 1.8 class list when packaged opaquely. */
    private static Set<String> eventClassNames() {
        Set<String> names = new LinkedHashSet<>();
        scanCodeSource(names);
        scanClassLoader(names);
        Collections.addAll(names, FALLBACK_EVENTS);
        return names;
    }

    private static void scanCodeSource(Set<String> names) {
        try {
            URL source = Bukkit.class.getProtectionDomain().getCodeSource().getLocation();
            if (source == null || !"file".equals(source.getProtocol())) return;
            File file = new File(source.toURI());
            if (file.isFile()) scanJar(file, names);
            else if (file.isDirectory()) scanDirectory(new File(file, "org/bukkit/event"), "org.bukkit.event", names);
        } catch (Throwable ignored) { }
    }

    private static void scanClassLoader(Set<String> names) {
        try {
            Enumeration<URL> resources = Bukkit.class.getClassLoader().getResources("org/bukkit/event/");
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    File directory = new File(url.toURI());
                    if (directory.isDirectory()) scanDirectory(directory, "org.bukkit.event", names);
                } else if ("jar".equals(url.getProtocol())) {
                    JarURLConnection connection = (JarURLConnection) url.openConnection();
                    connection.setUseCaches(false);
                    try (JarFile jar = connection.getJarFile()) { scanJar(jar, names); }
                }
            }
        } catch (Throwable ignored) { }
    }

    private static void scanJar(File file, Set<String> names) throws IOException {
        try (JarFile jar = new JarFile(file)) { scanJar(jar, names); }
    }

    private static void scanJar(JarFile jar, Set<String> names) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.startsWith("org/bukkit/event/") || !name.endsWith(".class") || name.indexOf('$') >= 0) continue;
            names.add(name.substring(0, name.length() - 6).replace('/', '.'));
        }
    }

    private static void scanDirectory(File directory, String prefix, Set<String> names) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) scanDirectory(file, prefix + "." + file.getName(), names);
            else if (file.getName().endsWith(".class") && file.getName().indexOf('$') < 0)
                names.add(prefix + "." + file.getName().substring(0, file.getName().length() - 6));
        }
    }

    private static final String[] FALLBACK_EVENTS = {
        "org.bukkit.event.block.BlockBreakEvent",
        "org.bukkit.event.block.BlockBurnEvent",
        "org.bukkit.event.block.BlockCanBuildEvent",
        "org.bukkit.event.block.BlockDamageEvent",
        "org.bukkit.event.block.BlockDispenseEvent",
        "org.bukkit.event.block.BlockExpEvent",
        "org.bukkit.event.block.BlockFadeEvent",
        "org.bukkit.event.block.BlockFormEvent",
        "org.bukkit.event.block.BlockFromToEvent",
        "org.bukkit.event.block.BlockGrowEvent",
        "org.bukkit.event.block.BlockIgniteEvent",
        "org.bukkit.event.block.BlockMultiPlaceEvent",
        "org.bukkit.event.block.BlockPhysicsEvent",
        "org.bukkit.event.block.BlockPistonExtendEvent",
        "org.bukkit.event.block.BlockPistonRetractEvent",
        "org.bukkit.event.block.BlockPlaceEvent",
        "org.bukkit.event.block.BlockRedstoneEvent",
        "org.bukkit.event.block.BlockSpreadEvent",
        "org.bukkit.event.block.EntityBlockFormEvent",
        "org.bukkit.event.block.LeavesDecayEvent",
        "org.bukkit.event.block.NotePlayEvent",
        "org.bukkit.event.block.SignChangeEvent",
        "org.bukkit.event.enchantment.EnchantItemEvent",
        "org.bukkit.event.enchantment.PrepareItemEnchantEvent",
        "org.bukkit.event.entity.CreatureSpawnEvent",
        "org.bukkit.event.entity.CreeperPowerEvent",
        "org.bukkit.event.entity.EntityBreakDoorEvent",
        "org.bukkit.event.entity.EntityChangeBlockEvent",
        "org.bukkit.event.entity.EntityCombustEvent",
        "org.bukkit.event.entity.EntityDamageEvent",
        "org.bukkit.event.entity.EntityDeathEvent",
        "org.bukkit.event.entity.EntityExplodeEvent",
        "org.bukkit.event.entity.EntityInteractEvent",
        "org.bukkit.event.entity.EntityPortalEvent",
        "org.bukkit.event.entity.EntityRegainHealthEvent",
        "org.bukkit.event.entity.EntityShootBowEvent",
        "org.bukkit.event.entity.EntitySpawnEvent",
        "org.bukkit.event.entity.EntityTameEvent",
        "org.bukkit.event.entity.EntityTargetEvent",
        "org.bukkit.event.entity.EntityTeleportEvent",
        "org.bukkit.event.entity.EntityUnleashEvent",
        "org.bukkit.event.entity.ExpBottleEvent",
        "org.bukkit.event.entity.ExplosionPrimeEvent",
        "org.bukkit.event.entity.FoodLevelChangeEvent",
        "org.bukkit.event.entity.HorseJumpEvent",
        "org.bukkit.event.entity.ItemDespawnEvent",
        "org.bukkit.event.entity.ItemSpawnEvent",
        "org.bukkit.event.entity.PigZapEvent",
        "org.bukkit.event.entity.PlayerDeathEvent",
        "org.bukkit.event.entity.PlayerLeashEntityEvent",
        "org.bukkit.event.entity.PotionSplashEvent",
        "org.bukkit.event.entity.ProjectileHitEvent",
        "org.bukkit.event.entity.ProjectileLaunchEvent",
        "org.bukkit.event.entity.SheepDyeWoolEvent",
        "org.bukkit.event.entity.SheepRegrowWoolEvent",
        "org.bukkit.event.entity.SlimeSplitEvent",
        "org.bukkit.event.entity.SpawnerSpawnEvent",
        "org.bukkit.event.hanging.HangingBreakEvent",
        "org.bukkit.event.hanging.HangingPlaceEvent",
        "org.bukkit.event.inventory.BrewEvent",
        "org.bukkit.event.inventory.CraftItemEvent",
        "org.bukkit.event.inventory.FurnaceBurnEvent",
        "org.bukkit.event.inventory.FurnaceExtractEvent",
        "org.bukkit.event.inventory.FurnaceSmeltEvent",
        "org.bukkit.event.inventory.InventoryClickEvent",
        "org.bukkit.event.inventory.InventoryCloseEvent",
        "org.bukkit.event.inventory.InventoryCreativeEvent",
        "org.bukkit.event.inventory.InventoryDragEvent",
        "org.bukkit.event.inventory.InventoryMoveItemEvent",
        "org.bukkit.event.inventory.InventoryOpenEvent",
        "org.bukkit.event.inventory.InventoryPickupItemEvent",
        "org.bukkit.event.inventory.PrepareItemCraftEvent",
        "org.bukkit.event.painting.PaintingBreakEvent",
        "org.bukkit.event.painting.PaintingPlaceEvent",
        "org.bukkit.event.player.AsyncPlayerChatEvent",
        "org.bukkit.event.player.AsyncPlayerPreLoginEvent",
        "org.bukkit.event.player.PlayerAchievementAwardedEvent",
        "org.bukkit.event.player.PlayerAnimationEvent",
        "org.bukkit.event.player.PlayerArmorStandManipulateEvent",
        "org.bukkit.event.player.PlayerBedEnterEvent",
        "org.bukkit.event.player.PlayerBedLeaveEvent",
        "org.bukkit.event.player.PlayerBucketEmptyEvent",
        "org.bukkit.event.player.PlayerBucketFillEvent",
        "org.bukkit.event.player.PlayerChangedWorldEvent",
        "org.bukkit.event.player.PlayerChatEvent",
        "org.bukkit.event.player.PlayerChatTabCompleteEvent",
        "org.bukkit.event.player.PlayerCommandPreprocessEvent",
        "org.bukkit.event.player.PlayerDropItemEvent",
        "org.bukkit.event.player.PlayerEditBookEvent",
        "org.bukkit.event.player.PlayerEggThrowEvent",
        "org.bukkit.event.player.PlayerExpChangeEvent",
        "org.bukkit.event.player.PlayerFishEvent",
        "org.bukkit.event.player.PlayerGameModeChangeEvent",
        "org.bukkit.event.player.PlayerInteractAtEntityEvent",
        "org.bukkit.event.player.PlayerInteractEntityEvent",
        "org.bukkit.event.player.PlayerInteractEvent",
        "org.bukkit.event.player.PlayerItemBreakEvent",
        "org.bukkit.event.player.PlayerItemConsumeEvent",
        "org.bukkit.event.player.PlayerItemDamageEvent",
        "org.bukkit.event.player.PlayerItemHeldEvent",
        "org.bukkit.event.player.PlayerJoinEvent",
        "org.bukkit.event.player.PlayerKickEvent",
        "org.bukkit.event.player.PlayerLevelChangeEvent",
        "org.bukkit.event.player.PlayerLoginEvent",
        "org.bukkit.event.player.PlayerMoveEvent",
        "org.bukkit.event.player.PlayerPickupItemEvent",
        "org.bukkit.event.player.PlayerPortalEvent",
        "org.bukkit.event.player.PlayerPreLoginEvent",
        "org.bukkit.event.player.PlayerQuitEvent",
        "org.bukkit.event.player.PlayerRegisterChannelEvent",
        "org.bukkit.event.player.PlayerRespawnEvent",
        "org.bukkit.event.player.PlayerShearEntityEvent",
        "org.bukkit.event.player.PlayerStatisticIncrementEvent",
        "org.bukkit.event.player.PlayerTeleportEvent",
        "org.bukkit.event.player.PlayerToggleFlightEvent",
        "org.bukkit.event.player.PlayerToggleSneakEvent",
        "org.bukkit.event.player.PlayerToggleSprintEvent",
        "org.bukkit.event.player.PlayerUnleashEntityEvent",
        "org.bukkit.event.player.PlayerUnregisterChannelEvent",
        "org.bukkit.event.player.PlayerVelocityEvent",
        "org.bukkit.event.server.MapInitializeEvent",
        "org.bukkit.event.server.PluginDisableEvent",
        "org.bukkit.event.server.PluginEnableEvent",
        "org.bukkit.event.server.RemoteServerCommandEvent",
        "org.bukkit.event.server.ServerCommandEvent",
        "org.bukkit.event.server.ServerListPingEvent",
        "org.bukkit.event.server.ServiceRegisterEvent",
        "org.bukkit.event.server.ServiceUnregisterEvent",
        "org.bukkit.event.vehicle.VehicleBlockCollisionEvent",
        "org.bukkit.event.vehicle.VehicleCreateEvent",
        "org.bukkit.event.vehicle.VehicleDamageEvent",
        "org.bukkit.event.vehicle.VehicleDestroyEvent",
        "org.bukkit.event.vehicle.VehicleEnterEvent",
        "org.bukkit.event.vehicle.VehicleEntityCollisionEvent",
        "org.bukkit.event.vehicle.VehicleExitEvent",
        "org.bukkit.event.vehicle.VehicleMoveEvent",
        "org.bukkit.event.vehicle.VehicleUpdateEvent",
        "org.bukkit.event.weather.LightningStrikeEvent",
        "org.bukkit.event.weather.ThunderChangeEvent",
        "org.bukkit.event.weather.WeatherChangeEvent",
        "org.bukkit.event.world.ChunkLoadEvent",
        "org.bukkit.event.world.ChunkPopulateEvent",
        "org.bukkit.event.world.ChunkUnloadEvent",
        "org.bukkit.event.world.PortalCreateEvent",
        "org.bukkit.event.world.SpawnChangeEvent",
        "org.bukkit.event.world.StructureGrowEvent",
        "org.bukkit.event.world.WorldInitEvent",
        "org.bukkit.event.world.WorldLoadEvent",
        "org.bukkit.event.world.WorldSaveEvent",
        "org.bukkit.event.world.WorldUnloadEvent",
        // Added after 1.8; loaded only when the running server provides them.
        "org.bukkit.event.entity.EntityPickupItemEvent",
        "org.bukkit.event.entity.EntityToggleGlideEvent",
        "org.bukkit.event.entity.EntityToggleSwimEvent",
        "org.bukkit.event.entity.EntityTransformEvent",
        "org.bukkit.event.entity.EntityPotionEffectEvent",
        "org.bukkit.event.entity.EntityAirChangeEvent",
        "org.bukkit.event.entity.EntityDropItemEvent",
        "org.bukkit.event.entity.EntitySpellCastEvent",
        "org.bukkit.event.entity.EntityResurrectEvent",
        "org.bukkit.event.entity.EntityBreedEvent",
        "org.bukkit.event.entity.EntityEnterLoveModeEvent",
        "org.bukkit.event.entity.EntityExhaustionEvent",
        "org.bukkit.event.block.BlockFertilizeEvent",
        "org.bukkit.event.block.BlockCookEvent",
        "org.bukkit.event.block.BlockDropItemEvent",
        "org.bukkit.event.player.PlayerSwapHandItemsEvent",
        "org.bukkit.event.player.PlayerResourcePackStatusEvent",
        "org.bukkit.event.player.PlayerLocaleChangeEvent",
        "org.bukkit.event.player.PlayerAdvancementDoneEvent",
        "org.bukkit.event.player.PlayerRecipeDiscoverEvent",
        "org.bukkit.event.player.PlayerTakeLecternBookEvent",
        "org.bukkit.event.player.PlayerHarvestBlockEvent",
        "org.bukkit.event.player.PlayerItemMendEvent",
        "org.bukkit.event.player.PlayerRiptideEvent",
        "org.bukkit.event.player.PlayerStopUsingItemEvent",
        "org.bukkit.event.player.PlayerBucketEntityEvent"
    };
}

package com.github.tranforlanguage;

import com.github.tranforlanguage.core.ServerArgs;
import com.github.tranforlanguage.utils.Messages;
import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.Plugin;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** {@code @server} methods for Bukkit/Spigot. Runs on the server thread only. */
final class SpigotServerApi {
    private static final Gson GSON = new Gson();
    private final Map<UUID, Object> bossBars = new HashMap<>();
    private final Set<String> pluginChannels = new LinkedHashSet<>();

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
                SpigotDisplay.sendTitle(p, Messages.format(a.text(1, "title")), Messages.format(a.text(2, "subtitle", "")));
                return "";
            }
            case "sendActionBar": {
                Player p = player(a);
                SpigotDisplay.sendActionBar(p, Messages.format(a.text(1, "text")));
                return "";
            }
            case "setPlayerListHeaderFooter": {
                Player p = player(a);
                SpigotDisplay.setPlayerListHeaderFooter(p,
                    Messages.format(a.text(1, "header")), Messages.format(a.text(2, "footer", "")));
                return "";
            }
            case "setPlayerListName": {
                player(a).setPlayerListName(Messages.format(a.text(1, "name")));
                return "";
            }
            case "playSound": {
                Player p = player(a);
                String sound = a.text(1, "sound");
                float volume = (float) a.decimal(2, "volume", 1.0);
                float pitch = (float) a.decimal(3, "pitch", 1.0);
                if (volume < 0) throw new IllegalArgumentException("playSound: volume must not be negative");
                if (pitch <= 0) throw new IllegalArgumentException("playSound: pitch must be positive");
                p.playSound(p.getLocation(), sound, volume, pitch);
                return "";
            }
            case "getPing": {
                return String.valueOf(SpigotDisplay.getPing(player(a)));
            }
            case "giveExp": {
                player(a).giveExp(a.integer(1, "amount"));
                return "";
            }
            case "setLevel": {
                int level = a.integer(1, "level");
                if (level < 0) throw new IllegalArgumentException("setLevel: level must not be negative");
                player(a).setLevel(level);
                return "";
            }
            case "setFlying": {
                player(a).setFlying(a.bool(1, "value"));
                return "";
            }
            case "setAllowFlight": {
                player(a).setAllowFlight(a.bool(1, "value"));
                return "";
            }
            case "setSneaking": {
                player(a).setSneaking(a.bool(1, "value"));
                return "";
            }
            case "setSprinting": {
                player(a).setSprinting(a.bool(1, "value"));
                return "";
            }
            case "setTime": {
                world(a.text(0, "world")).setTime((long) a.decimal(1, "time"));
                return "";
            }
            case "setWeather": {
                World w = world(a.text(0, "world"));
                Integer duration = null;
                if (a.has(2)) {
                    duration = a.integer(2, "duration");
                    if (duration < 0) throw new IllegalArgumentException("setWeather: duration must not be negative");
                }
                String weather = a.text(1, "weather").trim().toLowerCase(Locale.ROOT);
                switch (weather) {
                    case "clear": w.setStorm(false); w.setThundering(false); break;
                    case "rain": w.setStorm(true); w.setThundering(false); break;
                    case "thunder": w.setStorm(true); w.setThundering(true); break;
                    default: throw new IllegalArgumentException("unknown weather: " + a.text(1, "weather"));
                }
                if (duration != null) {
                    w.setWeatherDuration(duration);
                    w.setThunderDuration(duration);
                }
                return "";
            }
            case "getWorlds": {
                JsonArray worlds = new JsonArray();
                for (World world : Bukkit.getWorlds()) worlds.add(world.getName());
                return GSON.toJson(worlds);
            }
            case "getWorldInfo": {
                World w = world(a.text(0, "world"));
                JsonObject info = new JsonObject();
                info.addProperty("name", w.getName());
                info.addProperty("environment", w.getEnvironment().name());
                info.addProperty("time", w.getTime());
                info.addProperty("storm", w.hasStorm());
                info.addProperty("thundering", w.isThundering());
                info.addProperty("difficulty", w.getDifficulty().name());
                Location spawn = w.getSpawnLocation();
                info.addProperty("spawnX", spawn.getX());
                info.addProperty("spawnY", spawn.getY());
                info.addProperty("spawnZ", spawn.getZ());
                return GSON.toJson(info);
            }
            case "setDifficulty": {
                World w = world(a.text(0, "world"));
                try { w.setDifficulty(Difficulty.valueOf(a.text(1, "difficulty").trim().toUpperCase(Locale.ROOT))); }
                catch (IllegalArgumentException unknown) { throw new IllegalArgumentException("unknown difficulty: " + a.text(1, "difficulty")); }
                return "";
            }
            case "spawnEntity": {
                World w = world(a.text(0, "world"));
                EntityType type = entityType(a.text(4, "type"));
                Location location = new Location(w, a.decimal(1, "x"), a.decimal(2, "y"), a.decimal(3, "z"));
                Entity entity;
                try { entity = w.spawnEntity(location, type); }
                catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("cannot spawn " + a.text(4, "type") + ": " + failure.getMessage());
                }
                if (entity == null) throw new IllegalArgumentException("cannot spawn " + a.text(4, "type"));
                return entity.getUniqueId().toString();
            }
            case "strikeLightning": {
                World w = world(a.text(0, "world"));
                Location location = new Location(w, a.decimal(1, "x"), a.decimal(2, "y"), a.decimal(3, "z"));
                return w.strikeLightning(location).getUniqueId().toString();
            }
            case "getNearbyEntities": {
                World w = world(a.text(0, "world"));
                double x = a.decimal(1, "x"), y = a.decimal(2, "y"), z = a.decimal(3, "z");
                double radius = a.decimal(4, "radius");
                if (radius < 0) throw new IllegalArgumentException("getNearbyEntities: radius must not be negative");
                Location center = new Location(w, x, y, z);
                double limit = radius * radius;
                JsonArray entities = new JsonArray();
                for (Entity entity : w.getEntities()) {
                    if (entity.getLocation().distanceSquared(center) > limit) continue;
                    JsonObject info = new JsonObject();
                    info.addProperty("uuid", entity.getUniqueId().toString());
                    info.addProperty("type", entity.getType().name());
                    info.addProperty("name", entity.getName());
                    entities.add(info);
                }
                return GSON.toJson(entities);
            }
            case "getEntityInfo": {
                Entity entity = entity(world(a.text(0, "world")), a.text(1, "uuid"));
                Location location = entity.getLocation();
                JsonObject info = new JsonObject();
                info.addProperty("uuid", entity.getUniqueId().toString());
                info.addProperty("type", entity.getType().name());
                info.addProperty("name", entity.getName());
                info.addProperty("world", location.getWorld().getName());
                info.addProperty("x", location.getX());
                info.addProperty("y", location.getY());
                info.addProperty("z", location.getZ());
                info.addProperty("valid", entity.isValid());
                if (entity instanceof org.bukkit.entity.Damageable)
                    info.addProperty("health", ((org.bukkit.entity.Damageable) entity).getHealth());
                return GSON.toJson(info);
            }
            case "teleportEntity": {
                World w = world(a.text(0, "world"));
                Entity entity = entity(w, a.text(1, "uuid"));
                Location target = new Location(w, a.decimal(2, "x"), a.decimal(3, "y"), a.decimal(4, "z"));
                if (!entity.teleport(target)) throw new IllegalArgumentException("teleportEntity was cancelled");
                return "";
            }
            case "damageEntity": {
                World w = world(a.text(0, "world"));
                Entity entity = entity(w, a.text(1, "uuid"));
                double amount = a.decimal(2, "amount");
                if (amount < 0) throw new IllegalArgumentException("damageEntity: amount must not be negative");
                if (!(entity instanceof org.bukkit.entity.Damageable))
                    throw new IllegalArgumentException("entity is not damageable: " + a.text(1, "uuid"));
                ((org.bukkit.entity.Damageable) entity).damage(amount);
                return "";
            }
            case "removeEntity": {
                Entity entity = entity(world(a.text(0, "world")), a.text(1, "uuid"));
                if (entity instanceof Player) throw new IllegalArgumentException("removeEntity: cannot remove a player");
                entity.remove();
                return "";
            }
            case "getItem": {
                Player p = player(a);
                return GSON.toJson(item(p.getInventory().getItem(slot(a, 1, p.getInventory().getSize()))));
            }
            case "setItem": {
                Player p = player(a);
                int slot = slot(a, 1, p.getInventory().getSize());
                Material material = material(a.text(2, "item"), "item");
                if (material == Material.AIR) { p.getInventory().setItem(slot, null); return ""; }
                int count = a.integer(3, "count", 1);
                if (count < 1) throw new IllegalArgumentException("setItem: count must be at least 1");
                p.getInventory().setItem(slot, new ItemStack(material, count));
                return "";
            }
            case "getInventory": {
                Player p = player(a);
                JsonArray slots = new JsonArray();
                for (int slot = 0; slot < p.getInventory().getSize(); slot++) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("slot", slot);
                    entry.add("item", item(p.getInventory().getItem(slot)));
                    slots.add(entry);
                }
                return GSON.toJson(slots);
            }
            case "getPotionEffects": {
                JsonArray effects = new JsonArray();
                for (PotionEffect effect : player(a).getActivePotionEffects()) {
                    JsonObject info = new JsonObject();
                    info.addProperty("type", effect.getType().getName());
                    info.addProperty("duration", effect.getDuration());
                    info.addProperty("amplifier", effect.getAmplifier());
                    info.addProperty("ambient", effect.isAmbient());
                    info.addProperty("particles", effect.hasParticles());
                    effects.add(info);
                }
                return GSON.toJson(effects);
            }
            case "addPotionEffect": {
                Player p = player(a);
                PotionEffectType type = potion(a.text(1, "effect"));
                int duration = a.integer(2, "duration");
                int amplifier = a.integer(3, "amplifier", 0);
                boolean ambient = a.bool(4, "ambient", false);
                if (duration <= 0) throw new IllegalArgumentException("addPotionEffect: duration must be positive");
                if (amplifier < 0) throw new IllegalArgumentException("addPotionEffect: amplifier must not be negative");
                p.addPotionEffect(new PotionEffect(type, duration, amplifier, ambient), true);
                return "";
            }
            case "removePotionEffect": {
                player(a).removePotionEffect(potion(a.text(1, "effect")));
                return "";
            }
            case "clearPotionEffects": {
                Player p = player(a);
                for (PotionEffect effect : new ArrayList<>(p.getActivePotionEffects()))
                    p.removePotionEffect(effect.getType());
                return "";
            }
            case "getServerInfo": {
                JsonObject info = new JsonObject();
                info.addProperty("version", Bukkit.getVersion());
                info.addProperty("bukkitVersion", Bukkit.getBukkitVersion());
                info.addProperty("online", Bukkit.getOnlinePlayers().size());
                info.addProperty("maxPlayers", Bukkit.getMaxPlayers());
                JsonArray worlds = new JsonArray();
                for (World world : Bukkit.getWorlds()) worlds.add(world.getName());
                info.add("worlds", worlds);
                return GSON.toJson(info);
            }
            case "getWorldBorder": {
                WorldBorder border = world(a.text(0, "world")).getWorldBorder();
                Location center = border.getCenter();
                JsonObject info = new JsonObject();
                info.addProperty("size", border.getSize());
                info.addProperty("centerX", center.getX());
                info.addProperty("centerZ", center.getZ());
                info.addProperty("warningDistance", border.getWarningDistance());
                info.addProperty("damageAmount", border.getDamageAmount());
                return GSON.toJson(info);
            }
            case "setWorldBorder": {
                World w = world(a.text(0, "world"));
                WorldBorder border = w.getWorldBorder();
                double size = a.decimal(1, "size");
                if (size <= 0) throw new IllegalArgumentException("setWorldBorder: size must be positive");
                Double centerX = null, centerZ = null;
                if (a.has(2) || a.has(3)) {
                    if (!a.has(2) || !a.has(3))
                        throw new IllegalArgumentException("setWorldBorder: centerX and centerZ must be provided together");
                    centerX = a.decimal(2, "centerX");
                    centerZ = a.decimal(3, "centerZ");
                }
                Integer warningDistance = a.has(4) ? a.integer(4, "warningDistance") : null;
                Double damageAmount = null;
                if (a.has(5)) {
                    damageAmount = a.decimal(5, "damageAmount");
                    if (damageAmount < 0)
                        throw new IllegalArgumentException("setWorldBorder: damageAmount must not be negative");
                }
                border.setSize(size);
                if (centerX != null) border.setCenter(centerX, centerZ);
                if (warningDistance != null) border.setWarningDistance(warningDistance);
                if (damageAmount != null) border.setDamageAmount(damageAmount);
                return "";
            }
            case "sendPluginMessage": {
                Player p = player(a);
                String channel = a.text(1, "channel").trim();
                if (channel.isEmpty()) throw new IllegalArgumentException("sendPluginMessage: channel must not be empty");
                Plugin plugin = TranForLanguage.getInstance();
                if (plugin == null) throw new IllegalStateException("plugin is not enabled");
                if (!pluginChannels.contains(channel)) {
                    p.getServer().getMessenger().registerOutgoingPluginChannel(plugin, channel);
                    pluginChannels.add(channel);
                }
                p.sendPluginMessage(plugin, channel, a.text(2, "data", "").getBytes(StandardCharsets.UTF_8));
                return "";
            }
            case "setPlayerTime": {
                Player p = player(a);
                p.setPlayerTime((long) a.decimal(1, "time"), a.bool(2, "relative", true));
                return "";
            }
            case "resetPlayerTime": {
                player(a).resetPlayerTime();
                return "";
            }
            case "setPlayerWeather": {
                Player p = player(a);
                String weather = a.text(1, "weather").trim().toLowerCase(Locale.ROOT);
                if (weather.equals("clear")) p.setPlayerWeather(WeatherType.CLEAR);
                else if (weather.equals("rain") || weather.equals("downfall")) p.setPlayerWeather(WeatherType.DOWNFALL);
                else throw new IllegalArgumentException("unknown player weather: " + a.text(1, "weather"));
                return "";
            }
            case "resetPlayerWeather": {
                player(a).resetPlayerWeather();
                return "";
            }
            case "setFlySpeed": {
                Player p = player(a);
                float speed = (float) a.decimal(1, "speed");
                if (speed < -1 || speed > 1) throw new IllegalArgumentException("setFlySpeed: speed must be between -1 and 1");
                p.setFlySpeed(speed);
                return "";
            }
            case "setWalkSpeed": {
                Player p = player(a);
                float speed = (float) a.decimal(1, "speed");
                if (speed < -1 || speed > 1) throw new IllegalArgumentException("setWalkSpeed: speed must be between -1 and 1");
                p.setWalkSpeed(speed);
                return "";
            }
            case "setInvulnerable": {
                Player p = player(a);
                try { p.getClass().getMethod("setInvulnerable", boolean.class).invoke(p, a.bool(1, "value")); }
                catch (NoSuchMethodException unsupported) {
                    throw new IllegalArgumentException("setInvulnerable is not supported by this server version");
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalArgumentException("setInvulnerable failed: " + failure.getMessage(), failure);
                }
                return "";
            }
            case "sendBossBar": {
                Player p = player(a);
                double progress = a.decimal(2, "progress", 1.0);
                if (progress < 0 || progress > 1) throw new IllegalArgumentException("sendBossBar: progress must be between 0 and 1");
                bossBars.put(p.getUniqueId(), bossBar(p, Messages.format(a.text(1, "title")), progress,
                    a.text(3, "color", "PURPLE").trim().toUpperCase(Locale.ROOT),
                    a.text(4, "style", "SOLID").trim().toUpperCase(Locale.ROOT)));
                return "";
            }
            case "removeBossBar": {
                removeBossBar(player(a));
                return "";
            }
            case "spawnParticle": {
                Player p = player(a);
                double x = a.decimal(2, "x"), y = a.decimal(3, "y"), z = a.decimal(4, "z");
                int count = a.integer(5, "count", 1);
                double offsetX = a.decimal(6, "offsetX", 0), offsetY = a.decimal(7, "offsetY", 0), offsetZ = a.decimal(8, "offsetZ", 0);
                double speed = a.decimal(9, "speed", 0);
                if (count < 0) throw new IllegalArgumentException("spawnParticle: count must not be negative");
                spawnParticle(p, a.text(1, "particle").trim().toUpperCase(Locale.ROOT), x, y, z, count, offsetX, offsetY, offsetZ, speed);
                return "";
            }
            case "playEffect": {
                Player p = player(a);
                Effect effect;
                try { effect = Effect.valueOf(a.text(1, "effect").trim().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException unknown) { throw new IllegalArgumentException("unknown effect: " + a.text(1, "effect")); }
                Location location = new Location(p.getWorld(), a.decimal(2, "x"), a.decimal(3, "y"), a.decimal(4, "z"));
                p.playEffect(location, effect, a.integer(5, "data", 0));
                return "";
            }
            case "sendSignChange": {
                Player p = player(a);
                World w = world(a.text(1, "world"));
                Location location = new Location(w, a.decimal(2, "x"), a.decimal(3, "y"), a.decimal(4, "z"));
                String[] lines = new String[4];
                for (int i = 0; i < lines.length; i++) lines[i] = Messages.format(a.text(5 + i, "line" + (i + 1), ""));
                p.sendSignChange(location, lines);
                return "";
            }
            case "sendResourcePack": {
                player(a).setResourcePack(a.text(1, "url"));
                return "";
            }
            case "hasPermission": {
                String permission = a.text(1, "permission").trim();
                if (permission.isEmpty()) throw new IllegalArgumentException("hasPermission: permission must not be empty");
                return String.valueOf(player(a).hasPermission(permission));
            }
            case "getWhitelist": {
                JsonObject info = new JsonObject();
                info.addProperty("enabled", Bukkit.hasWhitelist());
                JsonArray players = new JsonArray();
                for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", p.getName());
                    entry.addProperty("uuid", p.getUniqueId().toString());
                    players.add(entry);
                }
                info.add("players", players);
                return GSON.toJson(info);
            }
            case "setWhitelist": {
                Bukkit.setWhitelist(a.bool(0, "enabled"));
                return "";
            }
            case "invoke": {
                return BukkitInvoker.invoke(a.text(0, "target"), a.text(1, "method"),
                    values.subList(2, values.size()));
            }
            case "createGui": {
                return BukkitInvoker.createGui(a.integer(0, "size"), Messages.format(a.text(1, "title", "")));
            }
            case "openGui": {
                BukkitInvoker.openGui(player(a), a.text(1, "gui"));
                return "";
            }
            case "closeGui": {
                player(a).closeInventory();
                return "";
            }
            case "removeGui": {
                BukkitInvoker.removeGui(a.text(0, "gui"));
                return "";
            }
            case "createMap": {
                return BukkitInvoker.createMap(world(a.text(0, "world")));
            }
            case "giveMap": {
                Player p = player(a);
                for (ItemStack left : p.getInventory().addItem(BukkitInvoker.mapItem(a.text(1, "map"))).values())
                    p.getWorld().dropItem(p.getLocation(), left);
                return "";
            }
            case "removeMap": {
                BukkitInvoker.removeMap(a.text(0, "map"));
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

    private static int slot(ServerArgs a, int index, int size) {
        int slot = a.integer(index, "slot");
        if (slot < 0 || slot >= size)
            throw new IllegalArgumentException("slot must be between 0 and " + (size - 1) + ": " + slot);
        return slot;
    }

    private static JsonElement item(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return JsonNull.INSTANCE;
        JsonObject item = new JsonObject();
        item.addProperty("type", stack.getType().name());
        item.addProperty("amount", stack.getAmount());
        item.addProperty("durability", stack.getDurability());
        return item;
    }

    private static PotionEffectType potion(String id) {
        String path = ServerArgs.vanillaPath(id);
        PotionEffectType type = path.contains(":") ? null : PotionEffectType.getByName(path);
        if (type == null) throw new IllegalArgumentException("unknown potion effect: " + id);
        return type;
    }

    private Object bossBar(Player player, String title, double progress, String colorName, String styleName) {
        try {
            Class<?> barType = Class.forName("org.bukkit.boss.BossBar");
            Class<?> colorType = Class.forName("org.bukkit.boss.BarColor");
            Class<?> styleType = Class.forName("org.bukkit.boss.BarStyle");
            Object color = enumValue(colorType, colorName);
            Object style = enumValue(styleType, styleName);
            Object previous = bossBars.remove(player.getUniqueId());
            if (previous != null) barType.getMethod("removePlayer", Player.class).invoke(previous, player);
            Class<?> flagType = Class.forName("org.bukkit.boss.BarFlag");
            Object flags = java.lang.reflect.Array.newInstance(flagType, 0);
            Object bar = Bukkit.class.getMethod("createBossBar", String.class, colorType, styleType, flags.getClass())
                .invoke(null, title, color, style, flags);
            barType.getMethod("setProgress", double.class).invoke(bar, progress);
            barType.getMethod("addPlayer", Player.class).invoke(bar, player);
            return bar;
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            throw new IllegalArgumentException("boss bars are not supported by this server version");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("unknown boss bar color or style", invalid);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("sendBossBar failed: " + failure.getMessage(), failure);
        }
    }

    void removeBossBar(Player player) {
        Object bar = bossBars.remove(player.getUniqueId());
        if (bar == null) return;
        try { Class.forName("org.bukkit.boss.BossBar").getMethod("removePlayer", Player.class).invoke(bar, player); }
        catch (ReflectiveOperationException ignored) { }
    }

    private static void spawnParticle(Player player, String name, double x, double y, double z,
                                      int count, double offsetX, double offsetY, double offsetZ, double speed) {
        try {
            Class<?> particleType = Class.forName("org.bukkit.Particle");
            Object particle = enumValue(particleType, name);
            Location location = new Location(player.getWorld(), x, y, z);
            try {
                Player.class.getMethod("spawnParticle", particleType, Location.class, int.class,
                    double.class, double.class, double.class, double.class)
                    .invoke(player, particle, location, count, offsetX, offsetY, offsetZ, speed);
            } catch (NoSuchMethodException simple) {
                Player.class.getMethod("spawnParticle", particleType, Location.class, int.class)
                    .invoke(player, particle, location, count);
            }
        } catch (ClassNotFoundException | NoSuchMethodException unsupported) {
            throw new IllegalArgumentException("particles are not supported by this server version");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("unknown particle: " + name);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("spawnParticle failed: " + failure.getMessage(), failure);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class<Enum>) type, name);
    }

    private static EntityType entityType(String id) {
        String path = ServerArgs.vanillaPath(id);
        EntityType type = path.contains(":") ? null : EntityType.fromName(path);
        if (type == null) {
            try { type = EntityType.valueOf(path.trim().toUpperCase(Locale.ROOT).replace('-', '_')); }
            catch (IllegalArgumentException unknown) { type = null; }
        }
        if (type == null) throw new IllegalArgumentException("unknown entity type: " + id);
        return type;
    }

    private static Entity entity(World world, String id) {
        UUID uuid;
        try { uuid = UUID.fromString(id.trim()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid entity uuid: " + id); }
        for (Entity entity : world.getEntities()) if (entity.getUniqueId().equals(uuid)) return entity;
        throw new IllegalArgumentException("unknown entity: " + id);
    }

    @SuppressWarnings("deprecation")
    private static double maxHealth(Player player) { return player.getMaxHealth(); }

    void clear() {
        Plugin plugin = TranForLanguage.getInstance();
        if (plugin != null) for (String channel : pluginChannels)
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, channel);
        pluginChannels.clear();
        bossBars.clear();
        BukkitInvoker.clear();
    }
}

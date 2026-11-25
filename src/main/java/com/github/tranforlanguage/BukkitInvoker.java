package com.github.tranforlanguage;

import com.google.gson.*;
import com.github.tranforlanguage.core.ServerArgs;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.map.MapView;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Vector;
import java.lang.reflect.*;
import java.util.*;

/**
 * Reflective access to the public Bukkit API. Method lookup uses the API interface/class, so
 * CraftBukkit/NMS members are never exposed. Complex arguments use a compact string encoding.
 */
final class BukkitInvoker {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Map<String, Inventory> GUIS = new LinkedHashMap<>();
    private static final Map<String, MapView> MAPS = new LinkedHashMap<>();
    private static final Set<String> DENIED = new HashSet<>(Arrays.asList(
        "getClass", "wait", "notify", "notifyAll", "finalize", "clone"));
    private static int sequence;

    private BukkitInvoker() {}

    static String createGui(int size, String title) {
        if (size < 9 || size > 54 || size % 9 != 0)
            throw new IllegalArgumentException("createGui: size must be a multiple of 9 between 9 and 54");
        Inventory inventory = Bukkit.createInventory(null, size, title == null ? "" : title);
        String id = "gui-" + (++sequence);
        GUIS.put(id, inventory);
        return id;
    }

    static void openGui(Player player, String id) {
        Inventory inventory = GUIS.get(id);
        if (inventory == null) throw new IllegalArgumentException("unknown gui: " + id);
        if (player.openInventory(inventory) == null) throw new IllegalArgumentException("openGui was cancelled");
    }

    static void removeGui(String id) {
        Inventory inventory = GUIS.remove(id);
        if (inventory == null) return;
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getOpenInventory().getTopInventory() == inventory) player.closeInventory();
    }

    static String createMap(World world) {
        MapView view = Bukkit.createMap(world);
        String id = "map-" + (++sequence);
        MAPS.put(id, view);
        return id;
    }

    static void removeMap(String id) { MAPS.remove(id); }

    static ItemStack mapItem(String id) {
        MapView view = MAPS.get(id);
        if (view == null) throw new IllegalArgumentException("unknown map: " + id);
        Material material = Material.matchMaterial("filled_map");
        if (material == null) material = Material.MAP;
        ItemStack item = new ItemStack(material, 1);
        try {
            Class<?> metaType = Class.forName("org.bukkit.inventory.meta.MapMeta");
            Method setMapView = metaType.getMethod("setMapView", MapView.class);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) throw new IllegalArgumentException("map item has no metadata");
            setMapView.invoke(meta, view);
            item.setItemMeta(meta);
        } catch (ClassNotFoundException | NoSuchMethodException olderServer) {
            // 1.8 maps carry the map id in the item durability.
            item.setDurability((short) view.getId());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("cannot create map item: " + failure.getMessage(), failure);
        }
        return item;
    }

    static void clear() {
        for (Inventory inventory : GUIS.values())
            for (Player player : Bukkit.getOnlinePlayers())
                if (player.getOpenInventory().getTopInventory() == inventory) player.closeInventory();
        GUIS.clear();
        MAPS.clear();
    }

    static String invoke(String descriptor, String methodName, List<String> arguments) throws Exception {
        Target target = target(descriptor);
        if (methodName == null || methodName.trim().isEmpty())
            throw new IllegalArgumentException("invoke: method must not be empty");
        List<Candidate> candidates = new ArrayList<>();
        for (Method method : target.type.getMethods()) {
            if (!method.getName().equals(methodName)) continue;
            if (method.isBridge() || method.isSynthetic() || method.isVarArgs()) continue;
            if (DENIED.contains(method.getName())) continue;
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            if (isStatic != target.staticOnly) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != arguments.size()) continue;
            int cost = 0;
            boolean usable = true;
            for (int i = 0; i < types.length; i++) {
                int value = cost(types[i], arguments.get(i));
                if (value < 0) { usable = false; break; }
                cost += value;
            }
            if (usable) candidates.add(new Candidate(method, cost));
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no matching Bukkit method " + methodName + " on " + descriptor
                + " with " + arguments.size() + " argument(s)");
        }
        Collections.sort(candidates, new Comparator<Candidate>() {
            public int compare(Candidate left, Candidate right) { return left.cost - right.cost; }
        });
        if (candidates.size() > 1 && candidates.get(0).cost == candidates.get(1).cost)
            throw new IllegalArgumentException("ambiguous Bukkit method " + methodName + " on " + descriptor
                + ": " + signatures(candidates, candidates.get(0).cost));
        Exception last = null;
        for (Candidate candidate : candidates) {
            try {
                Object[] values = new Object[arguments.size()];
                for (int i = 0; i < values.length; i++)
                    values[i] = value(candidate.method.getParameterTypes()[i], arguments.get(i));
                Object result = candidate.method.invoke(target.value, values);
                if (candidate.method.getReturnType() == void.class) return "";
                return GSON.toJson(BukkitEventBridge.convertComplete(result, 0));
            } catch (IllegalArgumentException failure) {
                last = failure;
            } catch (IllegalAccessException failure) {
                last = failure;
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause == null) throw new IllegalArgumentException(failure.toString());
                String message = cause.getMessage();
                throw new IllegalArgumentException(message == null || message.isEmpty() ? cause.toString() : message);
            }
        }
        throw new IllegalArgumentException("Bukkit method invocation failed: " + (last == null ? "no usable overload" : last.getMessage()));
    }

    private static final class Candidate {
        final Method method; final int cost;
        Candidate(Method method, int cost) { this.method = method; this.cost = cost; }
    }

    private static String signatures(List<Candidate> candidates, int cost) {
        StringBuilder text = new StringBuilder();
        for (Candidate candidate : candidates) {
            if (candidate.cost != cost) continue;
            if (text.length() > 0) text.append(", ");
            text.append(candidate.method.getName()).append('(');
            Class<?>[] types = candidate.method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (i > 0) text.append(',');
                text.append(types[i].getSimpleName());
            }
            text.append(')');
        }
        return text.toString();
    }

    private static final class Target {
        final Object value; final Class<?> type; final boolean staticOnly;
        Target(Object value, Class<?> type, boolean staticOnly) {
            this.value = value; this.type = type; this.staticOnly = staticOnly;
        }
    }

    private static Target target(String descriptor) {
        String value = descriptor == null ? "" : descriptor.trim();
        if (value.equals("server")) return new Target(server(), Server.class, false);
        if (value.equals("bukkit")) return new Target(null, Bukkit.class, true);
        if (value.startsWith("player:")) return new Target(player(value.substring(7)), Player.class, false);
        if (value.startsWith("offlineplayer:")) return new Target(offlinePlayer(value.substring(14)), OfflinePlayer.class, false);
        if (value.startsWith("world:")) return new Target(world(value.substring(6)), World.class, false);
        if (value.startsWith("blockstate:")) return new Target(block(value.substring(11)).getState(), BlockState.class, false);
        if (value.startsWith("block:")) return new Target(block(value.substring(6)), Block.class, false);
        if (value.startsWith("entity:")) return new Target(entity(value.substring(7)), Entity.class, false);
        if (value.startsWith("inventory:")) {
            Inventory inventory = inventory(value.substring(10));
            return new Target(inventory, inventory instanceof PlayerInventory ? PlayerInventory.class : Inventory.class, false);
        }
        if (value.startsWith("gui:")) return new Target(gui(value.substring(4)), Inventory.class, false);
        if (value.equals("scoreboard")) return new Target(mainScoreboard(), Scoreboard.class, false);
        if (value.startsWith("scoreboard:")) return new Target(player(value.substring(11)).getScoreboard(), Scoreboard.class, false);
        if (value.startsWith("objective:")) return new Target(objective(value.substring(10)), Objective.class, false);
        if (value.startsWith("team:")) return new Target(team(value.substring(5)), Team.class, false);
        if (value.startsWith("score:")) return new Target(score(value.substring(6)), Score.class, false);
        if (value.startsWith("worldborder:")) return new Target(world(value.substring(12)).getWorldBorder(), WorldBorder.class, false);
        if (value.startsWith("map:")) {
            MapView view = MAPS.get(value.substring(4));
            if (view == null) throw new IllegalArgumentException("unknown map: " + value.substring(4));
            return new Target(view, MapView.class, false);
        }
        throw new IllegalArgumentException("unknown Bukkit target: " + descriptor);
    }

    private static int cost(Class<?> type, String raw) {
        if (type == String.class || type == CharSequence.class) return 10;
        if (type == Object.class) return 20;
        try { value(type, raw); }
        catch (RuntimeException failure) { return -1; }
        if (type == int.class || type == Integer.class) return 1;
        if (type == long.class || type == Long.class) return 2;
        if (type == double.class || type == Double.class) return 3;
        if (type == float.class || type == Float.class) return 4;
        if (type == short.class || type == Short.class) return 5;
        if (type == byte.class || type == Byte.class) return 6;
        return 1;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object value(Class<?> type, String raw) {
        String text = raw == null ? "" : raw;
        if (type == String.class || type == CharSequence.class || type == Object.class) return text;
        if (type == boolean.class || type == Boolean.class) {
            if (text.equalsIgnoreCase("true")) return Boolean.TRUE;
            if (text.equalsIgnoreCase("false")) return Boolean.FALSE;
            throw new IllegalArgumentException("not a boolean: " + text);
        }
        if (type == char.class || type == Character.class) {
            if (text.length() != 1) throw new IllegalArgumentException("not a character: " + text);
            return text.charAt(0);
        }
        if (type == byte.class || type == Byte.class) return Byte.parseByte(text.trim());
        if (type == short.class || type == Short.class) return Short.parseShort(text.trim());
        if (type == int.class || type == Integer.class) return Integer.parseInt(text.trim());
        if (type == long.class || type == Long.class) return Long.parseLong(text.trim());
        if (type == float.class || type == Float.class) return Float.parseFloat(text.trim());
        if (type == double.class || type == Double.class) return Double.parseDouble(text.trim());
        if (type == Material.class) {
            Material material = Material.matchMaterial(ServerArgs.vanillaPath(text));
            if (material == null) throw new IllegalArgumentException("unknown material: " + text);
            return material;
        }
        if (type.isEnum()) {
            try { return Enum.valueOf((Class<Enum>) type, text.trim()); }
            catch (IllegalArgumentException exact) {
                try { return Enum.valueOf((Class<Enum>) type, text.trim().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) {
                    throw new IllegalArgumentException("unknown " + type.getSimpleName() + ": " + text);
                }
            }
        }
        if (type == UUID.class) return UUID.fromString(text.trim());
        if (type == World.class) return world(text);
        if (type == Player.class) return player(text);
        if (type == OfflinePlayer.class) return offlinePlayer(text);
        if (type == CommandSender.class) return text.equalsIgnoreCase("console") || text.equalsIgnoreCase("server")
            ? Bukkit.getConsoleSender() : player(text);
        if (type == Location.class) return location(text);
        if (type == Vector.class) return vector(text);
        if (type == Block.class) return block(text);
        if (type == BlockState.class) return block(text).getState();
        if (type == ItemStack.class) return item(text);
        if (type == PotionEffectType.class) {
            PotionEffectType effect = PotionEffectType.getByName(ServerArgs.vanillaPath(text));
            if (effect == null) throw new IllegalArgumentException("unknown potion effect: " + text);
            return effect;
        }
        if (type == Entity.class) return entity(text);
        if (type == Inventory.class || type == PlayerInventory.class) return inventory(text);
        if (type == Scoreboard.class) return text.isEmpty() || text.equals("main") ? mainScoreboard() : player(text).getScoreboard();
        if (type == Objective.class) return objective(text);
        if (type == Team.class) return team(text);
        if (type == Score.class) return score(text);
        if (type == WorldBorder.class) return world(text).getWorldBorder();
        if (type == MapView.class) {
            MapView view = MAPS.get(text);
            if (view == null) throw new IllegalArgumentException("unknown map: " + text);
            return view;
        }
        throw new IllegalArgumentException("unsupported Bukkit argument type: " + type.getSimpleName());
    }

    private static Server server() {
        Server server = Bukkit.getServer();
        if (server == null) throw new IllegalArgumentException("server is not running");
        return server;
    }

    private static Player player(String name) {
        Player player = Bukkit.getPlayerExact(name.trim());
        if (player == null) throw new IllegalArgumentException("player is not online: " + name);
        return player;
    }

    private static OfflinePlayer offlinePlayer(String value) {
        try { return Bukkit.getOfflinePlayer(UUID.fromString(value.trim())); }
        catch (IllegalArgumentException name) { return Bukkit.getOfflinePlayer(value.trim()); }
    }

    private static World world(String name) {
        World world = Bukkit.getWorld(name.trim());
        if (world == null) throw new IllegalArgumentException("unknown world: " + name);
        return world;
    }

    private static Block block(String value) {
        String[] parts = value.split(",", 4);
        if (parts.length != 4) throw new IllegalArgumentException("block must be world,x,y,z: " + value);
        World world = world(parts[0]);
        return world.getBlockAt(coordinate(parts[1], "x"), coordinate(parts[2], "y"), coordinate(parts[3], "z"));
    }

    private static Entity entity(String value) {
        String[] parts = value.split(",", 2);
        UUID id;
        try { id = UUID.fromString(parts.length == 2 ? parts[1].trim() : parts[0].trim()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid entity uuid: " + value); }
        if (parts.length == 2) {
            for (Entity entity : world(parts[0]).getEntities()) if (entity.getUniqueId().equals(id)) return entity;
        } else {
            for (World world : Bukkit.getWorlds())
                for (Entity entity : world.getEntities()) if (entity.getUniqueId().equals(id)) return entity;
        }
        throw new IllegalArgumentException("unknown entity: " + value);
    }

    private static Inventory inventory(String value) {
        Inventory gui = GUIS.get(value);
        if (gui != null) return gui;
        Player player = Bukkit.getPlayerExact(value);
        if (player != null) return player.getInventory();
        throw new IllegalArgumentException("unknown inventory: " + value);
    }

    private static Inventory gui(String id) {
        Inventory gui = GUIS.get(id);
        if (gui == null) throw new IllegalArgumentException("unknown gui: " + id);
        return gui;
    }

    private static Scoreboard mainScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) throw new IllegalArgumentException("scoreboard manager is unavailable");
        return manager.getMainScoreboard();
    }

    private static Objective objective(String name) {
        Objective objective = mainScoreboard().getObjective(name.trim());
        if (objective == null) throw new IllegalArgumentException("unknown objective: " + name);
        return objective;
    }

    private static Team team(String name) {
        Team team = mainScoreboard().getTeam(name.trim());
        if (team == null) throw new IllegalArgumentException("unknown team: " + name);
        return team;
    }

    private static Score score(String value) {
        String[] parts = value.split(",", 2);
        if (parts.length != 2) throw new IllegalArgumentException("score must be objective,entry: " + value);
        return objective(parts[0]).getScore(parts[1]);
    }

    private static Location location(String value) {
        String[] parts = value.split(",", 6);
        if (parts.length != 4 && parts.length != 6)
            throw new IllegalArgumentException("location must be world,x,y,z[,yaw,pitch]: " + value);
        World world = world(parts[0]);
        float yaw = parts.length == 6 ? (float) finite(parts[4], "yaw") : 0;
        float pitch = parts.length == 6 ? (float) finite(parts[5], "pitch") : 0;
        return new Location(world, finite(parts[1], "x"), finite(parts[2], "y"), finite(parts[3], "z"), yaw, pitch);
    }

    private static Vector vector(String value) {
        String[] parts = value.split(",", 3);
        if (parts.length != 3) throw new IllegalArgumentException("vector must be x,y,z: " + value);
        return new Vector(finite(parts[0], "x"), finite(parts[1], "y"), finite(parts[2], "z"));
    }

    private static double finite(String value, String name) {
        double number;
        try { number = Double.parseDouble(value.trim()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("invalid " + name + ": " + value); }
        if (!Double.isFinite(number)) throw new IllegalArgumentException("invalid " + name + ": " + value);
        return number;
    }

    private static ItemStack item(String value) {
        String[] parts = value.split(",", 2);
        Material material = Material.matchMaterial(ServerArgs.vanillaPath(parts[0]));
        if (material == null) throw new IllegalArgumentException("unknown material: " + parts[0]);
        int amount = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 1;
        if (amount < 1) throw new IllegalArgumentException("item amount must be at least 1");
        return new ItemStack(material, amount);
    }

    private static int coordinate(String value, String name) {
        double number = finite(value, name);
        double floor = Math.floor(number);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid " + name + " coordinate: " + value);
        return (int) floor;
    }
}

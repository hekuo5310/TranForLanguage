package com.github.tranforcpp.minecraft;

import com.github.tranforcpp.core.EventRateLimiter;
import com.github.tranforcpp.core.ProgramManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.*;

/** Server-thread notifications shared by all mod loaders. No game actions run here. */
public final class ModEvents {
    private static MinecraftServer server;
    private static ProgramManager programs;
    private static final Map<Entity, Integer> damageDepth = new IdentityHashMap<>();
    private static final Map<UUID, double[]> positions = new HashMap<>();
    private static final Map<ServerLevel, Boolean> weather = new IdentityHashMap<>();
    private static final EventRateLimiter<UUID> moves = new EventRateLimiter<>(
        Math.max(0L, Long.getLong("tranforcpp.move-interval-ms", 100L)) * 1_000_000L);
    private ModEvents() {}
    public static void attach(MinecraftServer owner, ProgramManager manager) {
        detach(); server = owner; programs = manager;
    }
    public static void detach() {
        programs = null; server = null; positions.clear(); weather.clear(); moves.clear(); damageDepth.clear();
    }
    public static void emit(String name, Object... args) {
        ProgramManager manager = programs;
        if (manager != null && server.isSameThread()) manager.sendEvent(name, args);
    }
    public static void beginDamage(Entity entity) {
        if (server != null && server.isSameThread()) damageDepth.merge(entity, 1, Integer::sum);
    }
    public static void endDamage(Entity entity, float amount, boolean accepted) {
        if (server == null || !server.isSameThread()) return;
        Integer depth = damageDepth.get(entity);
        if (depth == null) return;
        if (depth > 1) { damageDepth.put(entity, depth - 1); return; }
        damageDepth.remove(entity);
        if (accepted) emit("EntityDamage", entity.getName().getString(), String.valueOf(amount));
    }
    public static void tick(MinecraftServer owner) {
        if (owner != server || programs == null) return;
        Set<UUID> present = new HashSet<>();
        long now = System.nanoTime();
        for (ServerPlayer player : owner.getPlayerList().getPlayers()) {
            UUID id = player.getUUID(); present.add(id);
            double[] current = {player.getX(), player.getY(), player.getZ()};
            double[] previous = positions.put(id, current);
            if (previous != null && !Arrays.equals(previous, current) && moves.allow(id, now))
                emit("PlayerMove", player.getName().getString());
        }
        for (UUID id : new HashSet<>(positions.keySet())) if (!present.contains(id)) {
            positions.remove(id); moves.forget(id);
        }
        Set<ServerLevel> loaded = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerLevel level : owner.getAllLevels()) {
            loaded.add(level);
            boolean raining = level.isRaining();
            Boolean previous = weather.put(level, raining);
            if (previous != null && previous.booleanValue() != raining)
                emit("WeatherChange", level.dimension().toString(), String.valueOf(raining));
        }
        weather.keySet().retainAll(loaded);
    }
}

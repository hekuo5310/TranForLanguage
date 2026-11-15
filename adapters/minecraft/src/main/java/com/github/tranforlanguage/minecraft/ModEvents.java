package com.github.tranforlanguage.minecraft;

import com.github.tranforlanguage.core.EventRateLimiter;
import com.github.tranforlanguage.core.ProgramManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Server-thread notifications shared by all mod loaders. No game actions run here. */
public final class ModEvents {
    private static MinecraftServer server;
    private static ProgramManager programs;
    private static final Map<Entity, Integer> damageDepth = new IdentityHashMap<>();
    private static final Map<UUID, Position> positions = new HashMap<>();
    private static final Set<UUID> present = new HashSet<>();
    private static final Set<ServerLevel> loaded = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Last observed player state; changes are reported once per tick, never on first sight. */
    private static final class Position {
        double x, y, z; ServerLevel level;
        String gameMode; int experience; boolean sneaking, sprinting, flying, sleeping;
        Position(ServerPlayer player) { update(player); }
        boolean changed(ServerPlayer player) { return level != player.level() || x != player.getX() || y != player.getY() || z != player.getZ(); }
        void update(ServerPlayer player) {
            level = (ServerLevel) player.level(); x = player.getX(); y = player.getY(); z = player.getZ();
            gameMode = player.gameMode.getGameModeForPlayer().name(); experience = player.experienceLevel;
            sneaking = player.isShiftKeyDown(); sprinting = player.isSprinting();
            flying = player.getAbilities().flying; sleeping = player.isSleeping();
        }
        /** Emits state-change events by comparing with the new state, then stores it. */
        boolean report(ServerPlayer player) {
            String name = player.getName().getString();
            ServerLevel previousLevel = level; String previousMode = gameMode; int previousExperience = experience;
            boolean wasSneaking = sneaking, wasSprinting = sprinting, wasFlying = flying, wasSleeping = sleeping;
            boolean moved = changed(player);
            update(player);
            if (previousLevel != level) emit("PlayerChangeWorld", name, previousLevel.dimension().toString(), level.dimension().toString());
            if (!previousMode.equals(gameMode)) emit("PlayerGameModeChange", name, gameMode);
            if (previousExperience != experience) emit("PlayerLevelChange", name, String.valueOf(previousExperience), String.valueOf(experience));
            if (wasSneaking != sneaking) emit("PlayerToggleSneak", name, String.valueOf(sneaking));
            if (wasSprinting != sprinting) emit("PlayerToggleSprint", name, String.valueOf(sprinting));
            if (wasFlying != flying) emit("PlayerToggleFlight", name, String.valueOf(flying));
            if (!wasSleeping && sleeping) emit("PlayerBedEnter", name);
            if (wasSleeping && !sleeping) emit("PlayerBedLeave", name);
            return moved;
        }
    }
    private static final Map<ServerLevel, Boolean> weather = new IdentityHashMap<>();
    private static final Map<ServerLevel, Boolean> thunder = new IdentityHashMap<>();
    private static final EventRateLimiter<UUID> moves = new EventRateLimiter<>(
        TimeUnit.MILLISECONDS.toNanos(Math.max(0L, Long.getLong("tranforlanguage.move-interval-ms", 100L))));
    private ModEvents() {}
    public static void attach(MinecraftServer owner, ProgramManager manager) {
        detach(); server = owner; programs = manager;
    }
    public static void detach() {
        programs = null; server = null; positions.clear(); weather.clear(); thunder.clear(); moves.clear(); damageDepth.clear(); present.clear(); loaded.clear();
    }
    public static void emit(String name, Object... args) {
        ProgramManager manager = programs;
        MinecraftServer owner = server;
        if (manager != null && owner != null && owner.isSameThread()) manager.sendEvent(name, args);
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
        present.clear();
        long now = System.nanoTime();
        for (ServerPlayer player : owner.getPlayerList().getPlayers()) {
            UUID id = player.getUUID(); present.add(id);
            Position previous = positions.get(id);
            if (previous == null) positions.put(id, new Position(player));
            else if (previous.report(player) && moves.allow(id, now)) emit("PlayerMove", player.getName().getString());
        }
        Iterator<UUID> ids = positions.keySet().iterator();
        while (ids.hasNext()) { UUID id = ids.next(); if (!present.contains(id)) { ids.remove(); moves.forget(id); } }
        loaded.clear();
        for (ServerLevel level : owner.getAllLevels()) {
            loaded.add(level);
            boolean raining = level.isRaining();
            Boolean previous = weather.put(level, raining);
            if (previous != null && previous.booleanValue() != raining)
                emit("WeatherChange", level.dimension().toString(), String.valueOf(raining));
            boolean thundering = level.isThundering();
            Boolean wasThundering = thunder.put(level, thundering);
            if (wasThundering != null && wasThundering.booleanValue() != thundering)
                emit("ThunderChange", level.dimension().toString(), String.valueOf(thundering));
        }
        weather.keySet().retainAll(loaded);
        thunder.keySet().retainAll(loaded);
    }
}

package com.github.tranforcpp.fabric;

import com.github.tranforcpp.core.ProgramRuntime;
import com.github.tranforcpp.minecraft.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;

public final class TranforFabric implements ModInitializer {
    private volatile ProgramRuntime runtime;
    private void event(String name, Object... args) { ProgramRuntime r = runtime; if (r != null) r.sendEvent(name, args); }
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            runtime = new ProgramRuntime(new MinecraftPlatform(server, FabricLoader.getInstance().getGameDir().resolve("config/tranforcpp/programs").toFile()));
            runtime.reload().whenComplete((v, error) -> { if (error != null) java.util.logging.Logger.getLogger("TranforC++").severe(error.toString()); });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (runtime != null) { runtime.close(); runtime = null; } });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> BridgeCommands.register(dispatcher, () -> runtime));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> event("PlayerJoin", handler.getPlayer().getName().getString()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> event("PlayerQuit", handler.getPlayer().getName().getString()));
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> event("PlayerChat", sender.getName().getString(), message.signedContent()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> event("PlayerRespawn", newPlayer.getName().getString()));
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> event("BlockBreak", player.getName().getString(), state.getBlock().toString()));
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> event("EntitySpawn", entity.getType().toString()));
        ServerWorldEvents.LOAD.register((server, world) -> event("WorldLoad", world.dimension().toString()));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            event("EntityDeath", entity.getName().getString());
            if (entity instanceof ServerPlayer) event("PlayerDeath", entity.getName().getString(), source.getLocalizedDeathMessage(entity).getString());
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (player instanceof ServerPlayer) event("PlayerInteract", player.getName().getString(), "RIGHT_CLICK_BLOCK", player.getItemInHand(hand).getItem().toString());
            return InteractionResult.PASS;
        });
    }
}

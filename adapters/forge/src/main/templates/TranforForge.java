package com.github.tranforcpp.@LOADER@;

import com.github.tranforcpp.core.ProgramRuntime;
import com.github.tranforcpp.minecraft.*;
import @FML@.common.Mod;
import @EVENTBUS@.SubscribeEvent;
import @ROOT@.event.server.ServerStartedEvent;
import @ROOT@.event.server.ServerStoppingEvent;
import @ROOT@.event.RegisterCommandsEvent;
import @ROOT@.event.entity.player.PlayerEvent;
import @ROOT@.event.entity.player.PlayerContainerEvent;
import @ROOT@.event.entity.player.PlayerInteractEvent;
import @ROOT@.event.ServerChatEvent;
import @ROOT@.event.entity.living.LivingDeathEvent;
import @ROOT@.event.entity.EntityJoinLevelEvent;
import @ROOT@.event.level.BlockEvent;
import @ROOT@.event.level.LevelEvent;
import @ROOT@.event.CommandEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import java.io.File;

@Mod("tranforcpp")
public final class TranforForge {
    private volatile ProgramRuntime runtime;
    public TranforForge() { @REGISTER@; }
    private void emit(String name, Object... args) { ProgramRuntime r = runtime; if (r != null) r.sendEvent(name, args); }
    @SubscribeEvent public void start(ServerStartedEvent event) {
        runtime = new ProgramRuntime(new MinecraftPlatform(event.getServer(), new File("config/tranforcpp/programs")));
        runtime.reload().whenComplete((v, error) -> { if (error != null) java.util.logging.Logger.getLogger("TranForLanguage").severe(error.toString()); });
    }
    @SubscribeEvent public void stop(ServerStoppingEvent event) { if (runtime != null) { runtime.close(); runtime = null; } }
    @SubscribeEvent public void commands(RegisterCommandsEvent event) { BridgeCommands.register(event.getDispatcher(), () -> runtime); }
    @SubscribeEvent public void join(PlayerEvent.PlayerLoggedInEvent event) { if (event.getEntity() instanceof ServerPlayer) emit("PlayerJoin", event.getEntity().getName().getString()); }
    @SubscribeEvent public void quit(PlayerEvent.PlayerLoggedOutEvent event) { if (event.getEntity() instanceof ServerPlayer) emit("PlayerQuit", event.getEntity().getName().getString()); }
    @SubscribeEvent public void respawn(PlayerEvent.PlayerRespawnEvent event) { if (event.getEntity() instanceof ServerPlayer) emit("PlayerRespawn", event.getEntity().getName().getString()); }
    @SubscribeEvent public void chat(ServerChatEvent event) { emit("PlayerChat", event.getPlayer().getName().getString(), event.getMessage().getString()); }
    @SubscribeEvent public void breakBlock(BlockEvent.BreakEvent event) { if (event.getPlayer() instanceof ServerPlayer) emit("BlockBreak", event.getPlayer().getName().getString(), event.getState().getBlock().toString()); }
    @SubscribeEvent public void placeBlock(BlockEvent.EntityPlaceEvent event) { if (event.getEntity() instanceof ServerPlayer) emit("BlockPlace", event.getEntity().getName().getString(), event.getPlacedBlock().getBlock().toString()); }
    @SubscribeEvent public void death(LivingDeathEvent event) {
        if (event.getEntity().@LEVEL@() instanceof ServerLevel) {
            emit("EntityDeath", event.getEntity().getName().getString());
            if (event.getEntity() instanceof ServerPlayer) emit("PlayerDeath", event.getEntity().getName().getString(), event.getSource().getLocalizedDeathMessage(event.getEntity()).getString());
        }
    }
    @SubscribeEvent public void spawn(EntityJoinLevelEvent event) { if (event.getLevel() instanceof ServerLevel) emit("EntitySpawn", event.getEntity().getType().toString()); }
    @SubscribeEvent public void load(LevelEvent.Load event) { if (event.getLevel() instanceof ServerLevel) emit("WorldLoad", ((ServerLevel) event.getLevel()).dimension().toString()); }
    @SubscribeEvent public void open(PlayerContainerEvent.Open event) { if (event.getEntity() instanceof ServerPlayer) emit("InventoryOpen", event.getEntity().getName().getString()); }
    @SubscribeEvent public void close(PlayerContainerEvent.Close event) { if (event.getEntity() instanceof ServerPlayer) emit("InventoryClose", event.getEntity().getName().getString()); }
    @SubscribeEvent public void interact(PlayerInteractEvent.RightClickBlock event) { if (event.getEntity() instanceof ServerPlayer) emit("PlayerInteract", event.getEntity().getName().getString(), "RIGHT_CLICK_BLOCK", event.getItemStack().getItem().toString()); }
    @SubscribeEvent public void command(CommandEvent event) { emit("ServerCommand", event.getParseResults().getContext().getSource().getTextName(), event.getParseResults().getReader().getString()); }
}

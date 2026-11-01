package com.github.tranforcpp.mixin;
import com.github.tranforcpp.minecraft.ModEvents;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MinecraftServer.class)
abstract class ServerTickMixin {
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void tranforlanguage$tick(CallbackInfo ci) { ModEvents.tick((MinecraftServer)(Object)this); }
}

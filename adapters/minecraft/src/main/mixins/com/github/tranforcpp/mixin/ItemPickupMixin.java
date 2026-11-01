package com.github.tranforcpp.mixin;
import com.github.tranforcpp.minecraft.ModEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** This callback runs after inventory insertion, including complete and partial pickups. */
@Mixin(ServerPlayer.class)
abstract class ItemPickupMixin {
    @Inject(method = "onItemPickup", at = @At("RETURN"))
    private void tranforlanguage$pickup(ItemEntity item, CallbackInfo ci) {
        ModEvents.emit("PlayerPickupItem", ((ServerPlayer)(Object)this).getName().getString(), item.getItem().getItem().toString());
    }
}

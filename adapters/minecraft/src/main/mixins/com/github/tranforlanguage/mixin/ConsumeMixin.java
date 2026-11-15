package com.github.tranforlanguage.mixin;
import com.github.tranforlanguage.minecraft.ModEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** A finished use of food, potions and other consumables (Bukkit's PlayerItemConsumeEvent point). */
@Mixin(LivingEntity.class)
abstract class ConsumeMixin {
    @Shadow protected ItemStack useItem;
    @Inject(method = "completeUsingItem", at = @At("HEAD"))
    private void tranforlanguage$consume(CallbackInfo ci) {
        Object self = this;
        if (self instanceof ServerPlayer player && !useItem.isEmpty() && player.isUsingItem())
            ModEvents.emit("PlayerItemConsume", player.getName().getString(), useItem.getItem().toString());
    }
}

package com.github.tranforlanguage.mixin;
import com.github.tranforlanguage.minecraft.ModEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Taking a result out of the crafting grid (player inventory or crafting table). */
@Mixin(ResultSlot.class)
abstract class CraftMixin {
    @Inject(method = "onTake", at = @At("HEAD"))
    private void tranforlanguage$craft(Player player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayer && !stack.isEmpty())
            ModEvents.emit("PlayerCraftItem", player.getName().getString(), stack.getItem().toString());
    }
}

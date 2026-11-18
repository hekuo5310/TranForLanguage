package com.github.tranforlanguage.mixin;
import com.github.tranforlanguage.minecraft.ModEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * Crafting results taken from the grid (player inventory or crafting table). checkTakeAchievements runs with the
 * crafted stack for normal clicks (from onTake) and for shift-clicks (from onQuickCraft, before onTake is called
 * with the already-moved, possibly empty stack); removeCount is non-zero only once per crafting action.
 */
@Mixin(ResultSlot.class)
abstract class CraftMixin {
    @Shadow @Final private Player player;
    @Shadow private int removeCount;
    @Inject(method = "checkTakeAchievements", at = @At("HEAD"))
    private void tranforlanguage$craft(ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayer && removeCount > 0 && !stack.isEmpty())
            ModEvents.emit("PlayerCraftItem", player.getName().getString(), stack.getItem().toString());
    }
}

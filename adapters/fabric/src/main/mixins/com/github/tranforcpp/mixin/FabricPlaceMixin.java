package com.github.tranforcpp.mixin;
import com.github.tranforcpp.minecraft.ModEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(BlockItem.class)
abstract class FabricPlaceMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void tranforlanguage$place(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getPlayer() instanceof ServerPlayer && cir.getReturnValue().consumesAction())
            ModEvents.emit("BlockPlace", context.getPlayer().getName().getString(), context.getLevel().getBlockState(context.getClickedPos()).getBlock().toString());
    }
}

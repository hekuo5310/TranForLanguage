package com.github.tranforcpp.mixin;
import com.github.tranforcpp.minecraft.ModEvents;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.OptionalInt;
@Mixin(ServerPlayer.class)
abstract class FabricMenuMixin {
    @Inject(method = "openMenu", at = @At("RETURN"))
    private void tranforlanguage$open(CallbackInfoReturnable<OptionalInt> cir) {
        if (cir.getReturnValue().isPresent()) ModEvents.emit("InventoryOpen", ((ServerPlayer)(Object)this).getName().getString());
    }
    @Inject(method = "doCloseContainer", at = @At("HEAD"))
    private void tranforlanguage$close(CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer)(Object)this;
        if (player.containerMenu != player.inventoryMenu) ModEvents.emit("InventoryClose", player.getName().getString());
    }
}

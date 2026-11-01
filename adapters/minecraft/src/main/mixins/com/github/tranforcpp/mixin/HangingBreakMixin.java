package com.github.tranforcpp.mixin;
import com.github.tranforcpp.minecraft.ModEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Entity.class)
abstract class HangingBreakMixin {
    @Unique private boolean tranforlanguage$removed;
    @Inject(method = "remove", at = @At("RETURN"))
    private void tranforlanguage$remove(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity entity = (Entity)(Object)this;
        if (!tranforlanguage$removed && reason.shouldDestroy() && entity instanceof HangingEntity && entity.level() instanceof ServerLevel) {
            tranforlanguage$removed = true;
            ModEvents.emit("HangingBreak", entity.getType().toString(), reason.name());
        }
    }
}

package com.github.tranforcpp.mixin;
import com.github.tranforcpp.minecraft.ModEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayDeque;
@Mixin(Level.class)
abstract class BlockIgniteMixin {
    @Unique private final ThreadLocal<ArrayDeque<Boolean>> tranforlanguage$ignitions = ThreadLocal.withInitial(ArrayDeque::new);
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void tranforlanguage$before(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level)(Object)this;
        if (!(level instanceof ServerLevel) || !(state.getBlock() instanceof BaseFireBlock)) return;
        tranforlanguage$ignitions.get().push(!(level.getBlockState(pos).getBlock() instanceof BaseFireBlock));
    }
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void tranforlanguage$after(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object)this instanceof ServerLevel) || !(state.getBlock() instanceof BaseFireBlock)) return;
        ArrayDeque<Boolean> pending = tranforlanguage$ignitions.get();
        if (pending.isEmpty()) return;
        boolean ignition = pending.pop();
        if (pending.isEmpty()) tranforlanguage$ignitions.remove();
        if (ignition && cir.getReturnValueZ()) ModEvents.emit("BlockIgnite", "null", state.getBlock().toString());
    }
}

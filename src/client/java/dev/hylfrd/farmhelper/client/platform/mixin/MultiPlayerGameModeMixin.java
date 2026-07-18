package dev.hylfrd.farmhelper.client.platform.mixin;

import dev.hylfrd.farmhelper.client.FarmHelperClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Maps the fixed-upstream click event to the one client-thread FarmHelper ingress. */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void farmhelper$recordClickedBlock(
            BlockPos position,
            Direction direction,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        FarmHelperClient.recordClickedBlock(position, direction);
    }
}

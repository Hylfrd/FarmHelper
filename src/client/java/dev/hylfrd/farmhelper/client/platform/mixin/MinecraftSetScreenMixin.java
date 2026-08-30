package dev.hylfrd.farmhelper.client.platform.mixin;

import dev.hylfrd.farmhelper.client.FarmHelperClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the authoritative post-mutation Minecraft#setScreen lifecycle boundary. */
@Mixin(Minecraft.class)
abstract class MinecraftSetScreenMixin {
    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("TAIL"))
    private void farmhelper$recordScreenChange(Screen screen, CallbackInfo callbackInfo) {
        FarmHelperClient.recordScreenChange((Minecraft) (Object) this);
    }
}

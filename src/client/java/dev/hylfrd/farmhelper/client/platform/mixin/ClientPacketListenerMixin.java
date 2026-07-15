package dev.hylfrd.farmhelper.client.platform.mixin;

import dev.hylfrd.farmhelper.client.FarmHelperClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the one server-time heartbeat after vanilla has accepted the packet. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void farmhelper$recordServerTime(
            ClientboundSetTimePacket packet,
            CallbackInfo callbackInfo
    ) {
        FarmHelperClient.recordServerTimePacket();
    }
}

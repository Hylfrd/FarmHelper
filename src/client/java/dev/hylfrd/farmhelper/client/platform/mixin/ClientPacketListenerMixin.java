package dev.hylfrd.farmhelper.client.platform.mixin;

import dev.hylfrd.farmhelper.client.FarmHelperClient;
import dev.hylfrd.farmhelper.client.platform.ClientTickAdapter;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures fixed client-packet boundaries for trusted text and the server-time heartbeat. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void farmhelper$beginSystemMessageScope(
            ClientboundSystemChatPacket packet,
            CallbackInfo callbackInfo
    ) {
        ClientTickAdapter.beginSystemMessageScope(packet.overlay());
    }

    @Inject(method = "handleSystemChat", at = @At("TAIL"))
    private void farmhelper$endSystemMessageScope(
            ClientboundSystemChatPacket packet,
            CallbackInfo callbackInfo
    ) {
        ClientTickAdapter.endSystemMessageScope();
    }

    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void farmhelper$recordServerTime(
            ClientboundSetTimePacket packet,
            CallbackInfo callbackInfo
    ) {
        FarmHelperClient.recordServerTimePacket();
    }
}

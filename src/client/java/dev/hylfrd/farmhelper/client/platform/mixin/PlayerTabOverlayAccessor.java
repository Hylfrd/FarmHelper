package dev.hylfrd.farmhelper.client.platform.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Narrow read-only bridge for the tab footer. Minecraft 26.1.2 exposes footer mutation but no
 * getter, and Fabric has no public vanilla tab-list packet observation callback. Its sole consumer
 * is {@code MinecraftGameTextSnapshotSource.tabFooter()}, which bounds the text before delivering
 * it to {@code GameStateParser} for Garden, Jacob, economy, pest, and buff observations.
 */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
    @Accessor("footer")
    Component farmhelper$getFooter();
}

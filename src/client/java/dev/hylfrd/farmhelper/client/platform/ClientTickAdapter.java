package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.macro.MacroContext;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.PauseReason;
import dev.hylfrd.farmhelper.macro.WorldMode;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ItemSummary;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.StatusEffectSummary;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/** Adapts Fabric client ticks and Minecraft state into the common runtime model. */
public final class ClientTickAdapter {
    private ClientTickAdapter() {
    }

    public static void register(FarmHelperClientRuntime runtime) {
        ClientTickAdapter adapter = new ClientTickAdapter();
        ClientTickEvents.END_CLIENT_TICK.register(client -> adapter.tick(client, runtime));
    }

    private void tick(Minecraft client, FarmHelperClientRuntime runtime) {
        boolean worldReady = client.level != null;
        boolean playerReady = worldReady && client.player != null;
        boolean screenOpen = client.screen != null;
        PauseReason pauseReason = pauseReason(worldReady, playerReady, screenOpen);
        WorldMode worldMode = worldMode(client, worldReady);
        dev.hylfrd.farmhelper.macro.PlayerSnapshot legacyPlayerSnapshot = playerReady
                ? new dev.hylfrd.farmhelper.macro.PlayerSnapshot(
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ(),
                        client.player.getYRot(),
                        client.player.getXRot())
                : dev.hylfrd.farmhelper.macro.PlayerSnapshot.absent();
        ClientSnapshot clientSnapshot = snapshot(client, worldReady, playerReady);

        runtime.core().macroManager().tick(clientSnapshot, new MacroContext(
                playerReady,
                screenOpen,
                pauseReason,
                worldMode,
                legacyPlayerSnapshot));

        runtime.rotation().tick(client);
        if (shouldReleaseInputs(screenOpen, runtime)) {
            runtime.input().releaseAll(client);
        }
    }

    private boolean shouldReleaseInputs(boolean screenOpen, FarmHelperClientRuntime runtime) {
        return screenOpen
                || runtime.rotation().rotating()
                || runtime.core().macroManager().state() != MacroState.RUNNING;
    }

    private PauseReason pauseReason(boolean worldReady, boolean playerReady, boolean screenOpen) {
        if (!worldReady) {
            return PauseReason.NO_WORLD;
        }
        if (!playerReady) {
            return PauseReason.NO_PLAYER;
        }
        if (screenOpen) {
            return PauseReason.SCREEN_OPEN;
        }
        return PauseReason.NONE;
    }

    private WorldMode worldMode(Minecraft client, boolean worldReady) {
        if (!worldReady) {
            return WorldMode.NONE;
        }
        return client.hasSingleplayerServer() ? WorldMode.SINGLEPLAYER : WorldMode.MULTIPLAYER;
    }

    private ClientSnapshot snapshot(Minecraft client, boolean worldReady, boolean playerReady) {
        Observation<WorldSnapshot> world = worldReady
                ? Observation.present(new WorldSnapshot(Observation.present(identifier(
                        client.level.dimension().identifier()))))
                : Observation.absent();
        Observation<PlayerSnapshot> player = playerReady
                ? Observation.present(playerSnapshot(client.player))
                : Observation.absent();
        Observation<ConnectionSnapshot> connection = connectionSnapshot(client, worldReady);
        Observation<ScreenSnapshot> screen = client.screen == null
                ? Observation.absent()
                : Observation.present(new ScreenSnapshot(
                        Observation.present(client.screen.getClass().getName()),
                        Observation.present(client.screen.getTitle().getString())));
        return new ClientSnapshot(player, world, connection, screen);
    }

    private Observation<ConnectionSnapshot> connectionSnapshot(Minecraft client, boolean worldReady) {
        if (!worldReady) {
            return Observation.absent();
        }
        if (client.hasSingleplayerServer()) {
            return Observation.present(ConnectionSnapshot.singleplayer());
        }
        if (client.getConnection() != null) {
            return Observation.present(ConnectionSnapshot.multiplayer());
        }
        return Observation.unknown();
    }

    private PlayerSnapshot playerSnapshot(LocalPlayer player) {
        Vec3 velocity = player.getDeltaMovement();
        ItemStack mainHand = player.getMainHandItem();
        Observation<ItemSummary> mainHandItem = mainHand.isEmpty()
                ? Observation.absent()
                : Observation.present(new ItemSummary(
                        identifier(BuiltInRegistries.ITEM.getKey(mainHand.getItem())),
                        mainHand.getCount()));
        List<StatusEffectSummary> effects = player.getActiveEffects().stream()
                .map(this::statusEffectSummary)
                .sorted(Comparator.comparing(effect -> effect.identifier().toString()))
                .toList();
        return new PlayerSnapshot(
                Observation.present(new PositionSnapshot(player.getX(), player.getY(), player.getZ())),
                Observation.present(new MotionSnapshot(velocity.x, velocity.y, velocity.z)),
                Observation.present(new RotationSnapshot(player.getYRot(), player.getXRot())),
                mainHandItem,
                Observation.present(effects));
    }

    private StatusEffectSummary statusEffectSummary(MobEffectInstance effect) {
        Identifier effectIdentifier = effect.getEffect().unwrapKey()
                .map(key -> key.identifier())
                .orElseGet(() -> BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()));
        return new StatusEffectSummary(
                identifier(effectIdentifier),
                effect.getAmplifier(),
                effect.getDuration(),
                effect.isAmbient(),
                effect.isVisible(),
                effect.showIcon());
    }

    private ResourceIdentifier identifier(Identifier identifier) {
        return new ResourceIdentifier(identifier.getNamespace(), identifier.getPath());
    }
}

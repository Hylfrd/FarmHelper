package dev.hylfrd.farmhelper.client.platform;

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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Captures one deeply immutable client observation on the Minecraft client thread. */
public final class ClientSnapshotCapture {
    private static final int MAX_STATUS_EFFECTS = 128;
    private Object lastScreen;
    private Object lastMenu;
    private long nextScreenIdentity = 1L;
    private long screenIdentity;

    public ClientSnapshot capture(Minecraft client, long worldEpoch) {
        Objects.requireNonNull(client, "client");
        if (!client.isSameThread()) {
            return ClientSnapshot.unknown();
        }
        boolean worldReady = client.level != null;
        boolean playerReady = worldReady && client.player != null;
        Observation<WorldSnapshot> world = worldReady
                ? Observation.present(new WorldSnapshot(worldEpoch, Observation.present(identifier(
                        client.level.dimension().identifier()))))
                : Observation.absent();
        Observation<PlayerSnapshot> player = playerReady
                ? Observation.present(playerSnapshot(client.player))
                : Observation.absent();
        Observation<ConnectionSnapshot> connection = connection(client, worldReady);
        Observation<ScreenSnapshot> screen = screen(client.screen);
        return new ClientSnapshot(player, world, connection, screen);
    }

    private Observation<ScreenSnapshot> screen(Screen current) {
        if (current == null) {
            observeScreenIdentity(null, null);
            return Observation.absent();
        }
        Object menu = current instanceof AbstractContainerScreen<?> container
                ? container.getMenu()
                : null;
        long identity = observeScreenIdentity(current, menu);
        if (identity < 0L) {
            return Observation.unknown();
        }
        return Observation.present(new ScreenSnapshot(
                identity,
                Observation.present(current.getClass().getName()),
                bounded(current.getTitle())));
    }

    long observeScreenIdentity(Object current, Object menu) {
        if (current == null) {
            lastScreen = null;
            lastMenu = null;
            return 0L;
        }
        if (current != lastScreen || menu != lastMenu) {
            if (nextScreenIdentity == Long.MAX_VALUE) {
                return -1L;
            }
            screenIdentity = nextScreenIdentity++;
            lastScreen = current;
            lastMenu = menu;
        }
        return screenIdentity;
    }

    private static Observation<String> bounded(net.minecraft.network.chat.Component component) {
        if (component == null) {
            return Observation.unknown();
        }
        int maximum = dev.hylfrd.farmhelper.runtime.gamestate.GameTextInputBudget.MAX_LINE_CHARACTERS;
        String value = component.getString(maximum + 1);
        return value.length() > maximum ? Observation.unknown() : Observation.present(value);
    }

    private static Observation<ConnectionSnapshot> connection(Minecraft client, boolean worldReady) {
        if (!worldReady) {
            return client.getConnection() == null ? Observation.absent() : Observation.unknown();
        }
        if (client.hasSingleplayerServer()) {
            return Observation.present(ConnectionSnapshot.singleplayer());
        }
        return client.getConnection() == null
                ? Observation.unknown()
                : Observation.present(ConnectionSnapshot.multiplayer());
    }

    private static PlayerSnapshot playerSnapshot(LocalPlayer player) {
        Vec3 velocity = player.getDeltaMovement();
        ItemStack mainHand = player.getMainHandItem();
        Observation<ItemSummary> item = mainHand.isEmpty()
                ? Observation.absent()
                : Observation.present(new ItemSummary(
                        identifier(BuiltInRegistries.ITEM.getKey(mainHand.getItem())),
                        mainHand.getCount()));
        Observation<List<StatusEffectSummary>> effects = player.getActiveEffects().size() > MAX_STATUS_EFFECTS
                ? Observation.unknown()
                : Observation.present(player.getActiveEffects().stream()
                        .map(ClientSnapshotCapture::effect)
                        .sorted(Comparator.comparing(value -> value.identifier().toString()))
                        .toList());
        return new PlayerSnapshot(
                Observation.present(new PositionSnapshot(player.getX(), player.getY(), player.getZ())),
                Observation.present(new MotionSnapshot(velocity.x, velocity.y, velocity.z)),
                Observation.present(new RotationSnapshot(player.getYRot(), player.getXRot())),
                item,
                effects);
    }

    private static StatusEffectSummary effect(MobEffectInstance effect) {
        Identifier key = effect.getEffect().unwrapKey()
                .map(value -> value.identifier())
                .orElseGet(() -> BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()));
        return new StatusEffectSummary(identifier(key), effect.getAmplifier(), effect.getDuration(),
                effect.isAmbient(), effect.isVisible(), effect.showIcon());
    }

    private static ResourceIdentifier identifier(Identifier identifier) {
        Objects.requireNonNull(identifier, "registry identifier");
        return new ResourceIdentifier(identifier.getNamespace(), identifier.getPath());
    }
}

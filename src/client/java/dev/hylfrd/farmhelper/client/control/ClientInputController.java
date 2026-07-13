package dev.hylfrd.farmhelper.client.control;

import dev.hylfrd.farmhelper.control.MovementKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class ClientInputController {
    private final EnumSet<MovementKey> heldKeys = EnumSet.noneOf(MovementKey.class);

    public Set<MovementKey> heldKeys() {
        return Set.copyOf(heldKeys);
    }

    public String heldKeysText() {
        if (heldKeys.isEmpty()) {
            return "none";
        }
        return heldKeys.stream()
                .map(key -> key.name().toLowerCase())
                .collect(Collectors.joining(", "));
    }

    public void releaseAll(Minecraft client) {
        if (heldKeys.isEmpty()) {
            return;
        }
        for (MovementKey key : EnumSet.copyOf(heldKeys)) {
            set(client, key, false);
        }
        heldKeys.clear();
    }

    public void hold(Minecraft client, MovementKey key) {
        set(client, key, true);
        heldKeys.add(key);
    }

    private void set(Minecraft client, MovementKey key, boolean down) {
        KeyMapping mapping = mapping(client, key);
        if (mapping != null) {
            mapping.setDown(down);
        }
    }

    private KeyMapping mapping(Minecraft client, MovementKey key) {
        return switch (key) {
            case FORWARD -> client.options.keyUp;
            case BACK -> client.options.keyDown;
            case LEFT -> client.options.keyLeft;
            case RIGHT -> client.options.keyRight;
            case JUMP -> client.options.keyJump;
            case SNEAK -> client.options.keyShift;
            case SPRINT -> client.options.keySprint;
            case ATTACK -> client.options.keyAttack;
        };
    }
}

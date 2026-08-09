package dev.hylfrd.farmhelper.failsafe;

import java.util.Arrays;
import java.util.List;

/** Fixed upstream detector catalog used by the domain arbitration core. */
public enum FailsafeType {
    BAD_EFFECTS(1, true),
    BANWAVE(6, false),
    BEDROCK_CAGE(1, true),
    COBWEB(3, true),
    DIRT(3, true),
    DISCONNECT(1, true),
    EVACUATE(1, true),
    FULL_INVENTORY(3, true),
    GUEST_VISIT(1, true),
    ITEM_CHANGE(3, true),
    JACOB(7, true),
    KNOCKBACK(4, true),
    LOWER_AVG_BPS(9, true),
    ROTATION(4, true),
    TELEPORT(5, true),
    WORLD_CHANGE(2, true);

    private static final List<FailsafeType> REGISTRATION_ORDER =
            List.copyOf(Arrays.asList(values()));
    private static final List<FailsafeType> AVAILABLE_REGISTRATION_ORDER =
            REGISTRATION_ORDER.stream().filter(FailsafeType::available).toList();

    private final int priority;
    private final boolean available;

    FailsafeType(int priority, boolean available) {
        this.priority = priority;
        this.available = available;
    }

    /** Lower values win arbitration, matching the upstream detector priorities. */
    public int priority() {
        return priority;
    }

    /** Whether this detector may currently submit a local candidate. */
    public boolean available() {
        return available;
    }

    public boolean isAvailable() {
        return available();
    }

    /** Immutable fixed order from the upstream {@code FailsafeManager} registration list. */
    public static List<FailsafeType> upstreamRegistrationOrder() {
        return REGISTRATION_ORDER;
    }

    public static List<FailsafeType> allRegistrationOrder() {
        return upstreamRegistrationOrder();
    }

    /** Immutable order of detectors that have a permitted local implementation. */
    public static List<FailsafeType> availableRegistrationOrder() {
        return AVAILABLE_REGISTRATION_ORDER;
    }

    public static List<FailsafeType> localSafeRegistrationOrder() {
        return availableRegistrationOrder();
    }

    /** One-based order is useful when explaining deterministic tie resolution. */
    public int registrationOrder() {
        return ordinal() + 1;
    }
}

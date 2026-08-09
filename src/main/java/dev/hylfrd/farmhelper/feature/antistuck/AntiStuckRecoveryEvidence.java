package dev.hylfrd.farmhelper.feature.antistuck;

import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable client-captured evidence boundary; unknown and adapter errors can never become clear
 * space.
 */
public record AntiStuckRecoveryEvidence(
        AntiStuckIdentity identity,
        Status status,
        Optional<AntiStuckPlayerPose> player,
        AntiStuckTargetEvidence intersectionTarget,
        Map<AntiStuckSide, SideStatus> sideEvidence
) {
    public enum Status {
        KNOWN,
        UNKNOWN,
        ERROR
    }

    public enum SideStatus {
        CLEAR,
        BLOCKED,
        UNKNOWN,
        ERROR
    }

    public AntiStuckRecoveryEvidence {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(intersectionTarget, "intersectionTarget");
        Objects.requireNonNull(sideEvidence, "sideEvidence");
        player.ifPresent(value -> Objects.requireNonNull(value, "player value"));
        if (status == Status.KNOWN && player.isEmpty()) {
            throw new IllegalArgumentException("known recovery evidence needs a player pose");
        }
        if (status != Status.KNOWN && player.isPresent()) {
            throw new IllegalArgumentException("unknown/error recovery evidence cannot carry a pose");
        }

        EnumMap<AntiStuckSide, SideStatus> copied = new EnumMap<>(AntiStuckSide.class);
        for (AntiStuckSide side : AntiStuckSide.values()) {
            if (!sideEvidence.containsKey(side)) {
                throw new IllegalArgumentException("side evidence is incomplete: " + side);
            }
            copied.put(side, Objects.requireNonNull(sideEvidence.get(side), "side status"));
        }
        if (sideEvidence.size() != AntiStuckSide.values().length) {
            throw new IllegalArgumentException("side evidence contains an unknown side");
        }
        sideEvidence = Collections.unmodifiableMap(copied);
    }

    public static AntiStuckRecoveryEvidence known(
            AntiStuckIdentity identity,
            AntiStuckPlayerPose player,
            AntiStuckTargetEvidence intersectionTarget,
            Map<AntiStuckSide, SideStatus> sideEvidence
    ) {
        return new AntiStuckRecoveryEvidence(
                identity,
                Status.KNOWN,
                Optional.of(player),
                intersectionTarget,
                sideEvidence);
    }

    public static AntiStuckRecoveryEvidence known(
            AntiStuckIdentity identity,
            AntiStuckPlayerPose player,
            BlockPosition intersectionTarget,
            Map<AntiStuckSide, SideStatus> sideEvidence
    ) {
        return known(identity, player, AntiStuckTargetEvidence.present(intersectionTarget), sideEvidence);
    }

    public static AntiStuckRecoveryEvidence unknown(AntiStuckIdentity identity) {
        return new AntiStuckRecoveryEvidence(
                identity,
                Status.UNKNOWN,
                Optional.empty(),
                AntiStuckTargetEvidence.unknown(),
                allSides(SideStatus.UNKNOWN));
    }

    public static AntiStuckRecoveryEvidence error(AntiStuckIdentity identity) {
        return new AntiStuckRecoveryEvidence(
                identity,
                Status.ERROR,
                Optional.empty(),
                AntiStuckTargetEvidence.error(),
                allSides(SideStatus.ERROR));
    }

    public static Map<AntiStuckSide, SideStatus> allSides(SideStatus status) {
        Objects.requireNonNull(status, "status");
        EnumMap<AntiStuckSide, SideStatus> sides = new EnumMap<>(AntiStuckSide.class);
        for (AntiStuckSide side : AntiStuckSide.values()) {
            sides.put(side, status);
        }
        return Collections.unmodifiableMap(sides);
    }
}

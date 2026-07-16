package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;

import java.util.Objects;
import java.util.Optional;

/** Pure immutable eligibility observation; it contains no Minecraft or client-thread objects. */
public record NavigationStartObservation(
        Observation<WorldSnapshot> world,
        Observation<PlayerSnapshot> player,
        Observation<ConnectionSnapshot> connection,
        Observation<ScreenSnapshot> screen
) {
    public NavigationStartObservation {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(screen, "screen");
    }

    public Optional<NavigationFailureReason> failureFor(NavigationRequest request) {
        Objects.requireNonNull(request, "request");
        if (!world.isPresent()) {
            return Optional.of(NavigationFailureReason.WORLD_UNAVAILABLE);
        }
        if (!player.isPresent()) {
            return Optional.of(NavigationFailureReason.PLAYER_UNAVAILABLE);
        }
        if (!connection.isPresent()) {
            return Optional.of(NavigationFailureReason.CONNECTION_UNAVAILABLE);
        }
        if (screen.isPresent()) {
            return Optional.of(NavigationFailureReason.SCREEN_PRESENT);
        }
        if (!screen.isAbsent()) {
            return Optional.of(NavigationFailureReason.SCREEN_UNKNOWN);
        }
        if (world.get().epoch() != request.worldEpoch()) {
            return Optional.of(NavigationFailureReason.WORLD_EPOCH_MISMATCH);
        }
        return Optional.empty();
    }
}

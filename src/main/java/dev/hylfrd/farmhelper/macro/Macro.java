package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;

import java.util.Optional;
import java.util.Set;

public interface Macro {
    String id();

    void onStart();

    default void onStart(long nowNanos) {
        onStart();
    }

    void onStop();

    default void onPause(Set<MacroPauseCause> causes) {
    }

    default void onPause(Set<MacroPauseCause> causes, long nowNanos) {
        onPause(causes);
    }

    default void onResume() {
    }

    default void onResume(long nowNanos) {
        onResume();
    }

    default void onStop(MacroTerminalReason reason) {
        onStop();
    }

    default Optional<SpatialCaptureRequest> spatialRequest(
            PlayerSnapshot player,
            long worldEpoch
    ) {
        return Optional.empty();
    }

    default MacroDecision tick(FarmingContext context) {
        return MacroDecision.idle("unsupported-macro");
    }

}

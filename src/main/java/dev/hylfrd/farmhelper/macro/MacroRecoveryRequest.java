package dev.hylfrd.farmhelper.macro;

import java.util.Objects;

/** No-input handoff from a macro to the shared recovery feature introduced in P3. */
public record MacroRecoveryRequest(MacroRecoveryReason reason) {
    public MacroRecoveryRequest {
        Objects.requireNonNull(reason, "reason");
    }
}

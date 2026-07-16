package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;

import java.util.Objects;
import java.util.Optional;

/** Client acknowledgement for one exact macro-owned rotation request token. */
public record MacroRotationLeaseState(
        long requestToken,
        Status status,
        boolean paused,
        Optional<RotationTerminalReason> terminalReason,
        long controllerRevision
) {
    public MacroRotationLeaseState {
        if (requestToken < 0L || controllerRevision < 0L) {
            throw new IllegalArgumentException("rotation lease identity must not be negative");
        }
        Objects.requireNonNull(status, "status");
        terminalReason = Objects.requireNonNull(terminalReason, "terminalReason");
        if (status == Status.IDLE && requestToken != 0L) {
            throw new IllegalArgumentException("idle rotation lease must use token zero");
        }
        if (status == Status.ACTIVE && (requestToken == 0L || terminalReason.isPresent())) {
            throw new IllegalArgumentException("active rotation lease requires a token only");
        }
        if (status == Status.TERMINAL && (requestToken == 0L || terminalReason.isEmpty())) {
            throw new IllegalArgumentException("terminal rotation lease requires token and reason");
        }
        if (paused && status != Status.ACTIVE) {
            throw new IllegalArgumentException("only an active rotation lease can be paused");
        }
    }

    public static MacroRotationLeaseState idle(long revision) {
        return new MacroRotationLeaseState(
                0L, Status.IDLE, false, Optional.empty(), revision);
    }

    public static MacroRotationLeaseState active(long token, boolean paused, long revision) {
        return new MacroRotationLeaseState(
                token, Status.ACTIVE, paused, Optional.empty(), revision);
    }

    public static MacroRotationLeaseState terminal(
            long token,
            RotationTerminalReason reason,
            long revision
    ) {
        return new MacroRotationLeaseState(
                token, Status.TERMINAL, false, Optional.of(reason), revision);
    }

    public enum Status {
        IDLE,
        ACTIVE,
        TERMINAL
    }
}

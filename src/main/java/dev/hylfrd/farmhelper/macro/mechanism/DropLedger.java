package dev.hylfrd.farmhelper.macro.mechanism;

import dev.hylfrd.farmhelper.macro.PlayerPosture;

import java.util.Objects;
import java.util.Optional;

/** Shared exact drop trigger and landing-height ledger. */
public final class DropLedger {
    private double rowY;
    private boolean armed;

    public void arm(double y) {
        requireFinite(y);
        rowY = y;
        armed = true;
    }

    public boolean shouldDrop(PlayerPosture posture, double currentY) {
        Objects.requireNonNull(posture, "posture");
        requireFinite(currentY);
        return armed && !posture.flying() && !posture.onGround()
                && Math.abs(currentY - rowY) > 0.75D && currentY < 80.0D;
    }

    public Optional<Landing> observeLanding(PlayerPosture posture, double currentY) {
        Objects.requireNonNull(posture, "posture");
        requireFinite(currentY);
        if (!armed || !posture.onGround()) {
            return Optional.empty();
        }
        Landing landing = new Landing(Math.abs(rowY - currentY));
        rowY = currentY;
        return Optional.of(landing);
    }

    public void clear() {
        rowY = 0.0D;
        armed = false;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("drop height must be finite");
        }
    }

    public record Landing(double height) {
        public Landing {
            requireFinite(height);
            if (height < 0.0D) {
                throw new IllegalArgumentException("landing height must not be negative");
            }
        }

        public boolean changedLayer() {
            return height > 1.5D;
        }
    }
}

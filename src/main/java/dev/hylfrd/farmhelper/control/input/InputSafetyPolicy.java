package dev.hylfrd.farmhelper.control.input;

import java.util.Objects;
import java.util.function.Supplier;

/** Pure policy entry point for lifecycle and exception-triggered global release. */
public final class InputSafetyPolicy {
    public void release(InputController controller, ReleaseReason reason) {
        Objects.requireNonNull(controller, "controller").releaseAll(
                Objects.requireNonNull(reason, "reason"));
    }

    public void runSafely(InputController controller, Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        runSafely(controller, () -> {
            operation.run();
            return null;
        });
    }

    public <T> T runSafely(InputController controller, Supplier<T> operation) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(operation, "operation");
        try {
            return operation.get();
        } catch (RuntimeException | Error exception) {
            try {
                controller.releaseAll(ReleaseReason.EXCEPTION);
            } catch (RuntimeException | Error releaseFailure) {
                exception.addSuppressed(releaseFailure);
            }
            throw exception;
        }
    }
}

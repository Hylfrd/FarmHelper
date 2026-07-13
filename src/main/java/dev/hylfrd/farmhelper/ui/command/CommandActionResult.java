package dev.hylfrd.farmhelper.ui.command;

import java.util.Objects;

/** Result returned by an adapter after it delegates an action to the owning service. */
public record CommandActionResult(boolean successful, String message) {
    public CommandActionResult {
        Objects.requireNonNull(message, "message");
    }

    public static CommandActionResult success(String message) {
        return new CommandActionResult(true, message);
    }

    public static CommandActionResult failure(String message) {
        return new CommandActionResult(false, message);
    }
}

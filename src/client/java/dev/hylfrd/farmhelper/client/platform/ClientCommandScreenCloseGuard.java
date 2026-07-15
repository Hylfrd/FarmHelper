package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.runtime.lifecycle.ClientRuntimeLifecycle;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;

import java.util.Objects;

/** One-shot exemption for the expected close of an exact lifecycle-affecting chat command. */
public final class ClientCommandScreenCloseGuard {
    private Object observedChatScreen;
    private long observedChatScreenIdentity = -1L;
    private long expectedCloseIdentity = -1L;
    private long expectedGeneration = -1L;

    /** Arms only when the command still runs inside the exact chat screen last seen by lifecycle. */
    public void armAfterCommand(Object currentScreen, boolean chatScreen, long generation) {
        if (chatScreen && currentScreen != null && currentScreen == observedChatScreen) {
            expectedCloseIdentity = observedChatScreenIdentity;
            expectedGeneration = generation;
        } else {
            clearExpectedClose();
        }
    }

    /**
     * Observes screen lifecycle, consuming only the exact armed present-to-absent close. Every
     * mismatch is delegated to the ordinary lifecycle cancellation path.
     */
    public void observeScreen(
            Observation<ScreenSnapshot> screen,
            Object currentScreen,
            boolean chatScreen,
            ClientRuntimeLifecycle lifecycle,
            long currentGeneration
    ) {
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(lifecycle, "lifecycle");

        if (expectedCloseIdentity >= 0L
                && expectedGeneration == currentGeneration
                && screen.isAbsent()
                && lifecycle.observeExpectedScreenClose(expectedCloseIdentity, screen)) {
            clear();
            return;
        }

        if (expectedCloseIdentity >= 0L) {
            clearExpectedClose();
        }

        lifecycle.observeScreen(screen);
        if (screen.isPresent() && chatScreen && currentScreen != null) {
            observedChatScreen = currentScreen;
            observedChatScreenIdentity = screen.get().identity();
        } else {
            observedChatScreen = null;
            observedChatScreenIdentity = -1L;
        }
    }

    /** Clears both the one-shot token and the screen identity it was derived from. */
    public void clear() {
        clearExpectedClose();
        observedChatScreen = null;
        observedChatScreenIdentity = -1L;
    }

    private void clearExpectedClose() {
        expectedCloseIdentity = -1L;
        expectedGeneration = -1L;
    }
}

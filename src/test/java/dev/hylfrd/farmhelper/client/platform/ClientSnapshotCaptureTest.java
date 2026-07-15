package dev.hylfrd.farmhelper.client.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ClientSnapshotCaptureTest {
    @Test
    void screenAndMenuLifetimesGetStableDistinctIdentities() {
        ClientSnapshotCapture capture = new ClientSnapshotCapture();
        Object firstScreen = new Object();
        Object firstMenu = new Object();

        long first = capture.observeScreenIdentity(firstScreen, firstMenu);
        assertEquals(first, capture.observeScreenIdentity(firstScreen, firstMenu));

        long replacedMenu = capture.observeScreenIdentity(firstScreen, new Object());
        long replacedScreen = capture.observeScreenIdentity(new Object(), firstMenu);
        capture.observeScreenIdentity(null, null);
        long reopened = capture.observeScreenIdentity(firstScreen, firstMenu);

        assertNotEquals(first, replacedMenu);
        assertNotEquals(replacedMenu, replacedScreen);
        assertNotEquals(first, reopened);
    }
}

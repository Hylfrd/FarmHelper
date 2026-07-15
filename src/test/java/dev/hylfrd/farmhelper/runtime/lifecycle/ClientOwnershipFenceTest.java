package dev.hylfrd.farmhelper.runtime.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientOwnershipFenceTest {
    @Test
    void connectionReadinessAndCancellationGenerationBothFenceAcquisition() {
        ClientOwnershipFence fence = new ClientOwnershipFence();
        assertThrows(IllegalStateException.class, fence::requireAcquisitionAllowed);

        fence.setAutomationReady(true);
        fence.requireAcquisitionAllowed();
        ClientOwnershipFence.Boundary outer = fence.beginCancellation();
        ClientOwnershipFence.Boundary nested = fence.beginCancellation();

        assertTrue(outer.owner());
        assertFalse(nested.owner());
        assertEquals(outer.generation(), nested.generation());
        assertThrows(IllegalStateException.class, fence::requireAcquisitionAllowed);

        fence.endCancellation(nested);
        assertTrue(fence.cancelling());
        fence.endCancellation(outer);
        assertFalse(fence.cancelling());
        fence.requireAcquisitionAllowed();

        fence.setAutomationReady(false);
        assertThrows(IllegalStateException.class, fence::requireAcquisitionAllowed);
    }
}

package dev.hylfrd.farmhelper.macro.mechanism;

import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.MacroRotationLeaseState;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedRotationLedgerTest {
    @Test
    void backTargetCrossingOvershootAndPauseStayBlockedUntilExplicitEndpoint() {
        OwnedRotationLedger ledger = new OwnedRotationLedger();
        Queue<Double> draws = new ArrayDeque<>();
        draws.add(0.5D);
        draws.add(0.0D);
        var request = ledger.begin(
                0.0F, 0.0F, 90.0F, 10.0F, RotationProfile.BACK,
                700L, new RotationEntropy(draws::remove));

        assertEquals(OwnedRotationLedger.Gate.ACTIVE,
                ledger.observe(context(MacroRotationLeaseState.active(
                        request.requestToken(), false, 1L))),
                "crossing the target is still an active Back lease");
        assertEquals(OwnedRotationLedger.Gate.ACTIVE,
                ledger.observe(context(MacroRotationLeaseState.active(
                        request.requestToken(), false, 2L))),
                "overshoot remains blocked");
        assertEquals(OwnedRotationLedger.Gate.ACTIVE,
                ledger.observe(context(MacroRotationLeaseState.active(
                        request.requestToken(), true, 3L))),
                "pause remains blocked");
        assertEquals(OwnedRotationLedger.Gate.COMPLETED,
                ledger.observe(context(MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.COMPLETED, 4L))));
        assertTrue(ledger.pending().isEmpty());
    }

    @Test
    void cancellationCanReuseSampleWhileReplacementAndStaleAcknowledgementsNeverAdvance() {
        OwnedRotationLedger ledger = new OwnedRotationLedger();
        Queue<Double> draws = new ArrayDeque<>();
        draws.add(0.0D);
        draws.add(0.0D);
        var request = ledger.begin(
                0.0F, 0.0F, 45.0F, 5.0F, RotationProfile.EXPO_QUART,
                500L, new RotationEntropy(draws::remove));

        assertEquals(OwnedRotationLedger.Gate.STALE_ACKNOWLEDGEMENT,
                ledger.observe(context(MacroRotationLeaseState.active(
                        request.requestToken() + 1L, false, 1L))));
        assertEquals(request, ledger.pending().orElseThrow());

        assertEquals(OwnedRotationLedger.Gate.RETRYABLE_CANCELLATION,
                ledger.observe(context(MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.OWNER_CANCELLED, 2L))));
        assertEquals(request, ledger.pending().orElseThrow());
        assertTrue(draws.isEmpty(), "retry does not redraw handler entropy");

        assertEquals(OwnedRotationLedger.Gate.CANCELLED,
                ledger.observe(context(MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.REPLACED, 3L))));
        assertTrue(ledger.pending().isEmpty());
    }

    private static FarmingContext context(MacroRotationLeaseState lease) {
        return new FarmingContext(
                0L, 0L, Observation.unknown(), Observation.unknown(),
                Observation.present(true), false, ServerResponsiveness.RESPONSIVE,
                Observation.unknown(), Observation.present(lease));
    }
}

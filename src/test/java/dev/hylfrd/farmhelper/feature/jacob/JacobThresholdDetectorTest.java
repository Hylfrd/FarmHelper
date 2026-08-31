package dev.hylfrd.farmhelper.feature.jacob;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacobThresholdDetectorTest {
    private static final JacobContestIdentity FIRST = new JacobContestIdentity(1L);
    private static final JacobContestIdentity SECOND = new JacobContestIdentity(2L);

    private final JacobEvidenceParser parser = new JacobEvidenceParser();

    @Test
    void parsesExplicitCollectedCountAndCopiesTheInputBoundary() {
        List<String> lines = new ArrayList<>(List.of(
                "Jacob's Contest", "Wheat 2m30s", "Collected 1,234"));

        JacobEvidenceParseResult result = parser.parse(FIRST, 1L, lines);
        lines.set(2, "Collected 9,999");

        assertTrue(result.evidence().isPresent());
        assertEquals(FIRST, result.evidence().get().contest());
        assertEquals(JacobCrop.WHEAT, result.evidence().get().crop());
        assertEquals(1_234L, result.evidence().get().collectedCount());
        assertEquals(JacobEvidenceIssue.NONE, result.issue());
    }

    @Test
    void preservesRecognizedMedalFallbackAndRejectsMalformedCountForms() {
        for (String medal : List.of("BRONZE", "SILVER", "GOLD", "PLATINUM", "DIAMOND")) {
            JacobEvidenceParseResult result = parser.parse(FIRST, 1L,
                    List.of("Jacob's Contest", "Wheat 2m30s", medal + " with 2,345"));
            assertEquals(2_345L, result.evidence().get().collectedCount(), medal);
        }

        JacobEvidenceParseResult malformedNumber = parser.parse(FIRST, 2L,
                List.of("Jacob's Contest", "Wheat 2m30s", "GOLD with 12,34"));
        assertTrue(malformedNumber.evidence().isUnknown());
        assertEquals(JacobEvidenceIssue.MALFORMED, malformedNumber.issue());

        JacobEvidenceParseResult unknownLabel = parser.parse(FIRST, 3L,
                List.of("Jacob's Contest", "Wheat 2m30s", "GOLDEN with 1,234"));
        assertTrue(unknownLabel.evidence().isUnknown());
        assertEquals(JacobEvidenceIssue.UNKNOWN_FORMAT, unknownLabel.issue());
    }

    @Test
    void rejectsMalformedTimeUnknownCropAndConflictingContext() {
        JacobEvidenceParseResult badTime = parser.parse(FIRST, 1L,
                List.of("Jacob's Contest", "Wheat 2m99s", "Collected 10"));
        assertEquals(JacobEvidenceIssue.MALFORMED, badTime.issue());

        JacobEvidenceParseResult badCrop = parser.parse(FIRST, 2L,
                List.of("Jacob's Contest", "Mysterious Crop 2m30s", "Collected 10"));
        assertEquals(JacobEvidenceIssue.UNKNOWN_FORMAT, badCrop.issue());

        JacobEvidenceParseResult conflictingCrop = parser.parse(FIRST, 3L,
                List.of("Jacob's Contest", "Wheat 2m30s", "Carrot 2m30s", "Collected 10"));
        assertEquals(JacobEvidenceIssue.CONFLICT, conflictingCrop.issue());

        JacobEvidenceParseResult incomplete = parser.parse(FIRST, 4L,
                List.of("Jacob's Contest", "Wheat 2m30s"));
        assertEquals(JacobEvidenceIssue.INCOMPLETE, incomplete.issue());
    }

    @Test
    void distinguishesNoContestFromUnknownAndBoundsNumericInput() {
        JacobEvidenceParseResult absent = parser.parse(FIRST, 1L, List.of("Area: Garden"));
        assertTrue(absent.evidence().isAbsent());

        JacobEvidenceParseResult sourceUnknown = parser.unknown(FIRST, 2L);
        assertTrue(sourceUnknown.evidence().isUnknown());
        assertEquals(JacobEvidenceIssue.SOURCE_UNKNOWN, sourceUnknown.issue());

        JacobEvidenceParseResult overflow = parser.parse(FIRST, 3L,
                List.of("Jacob's Contest", "Wheat 2m30s", "Collected 9,223,372,036,854,775,808"));
        assertEquals(JacobEvidenceIssue.OVERFLOW, overflow.issue());

        JacobEvidenceParseResult inputLimit = parser.parse(FIRST, 4L,
                List.of("Jacob's Contest", "Wheat 2m30s", "Collected " + "9".repeat(65)));
        assertEquals(JacobEvidenceIssue.INPUT_LIMIT, inputLimit.issue());
    }

    @Test
    void usesInclusiveBoundaryAndStrictlyBelowWaits() {
        JacobThresholdDetector detector = new JacobThresholdDetector(
                JacobThresholds.of(JacobCrop.WHEAT, 100L));

        JacobThresholdResult below = detector.observe(evidence(FIRST, JacobCrop.WHEAT, 99L, 1L));
        assertEquals(JacobThresholdUpdate.ACCEPTED, below.update());
        assertEquals(JacobThresholdStatus.WAITING, below.snapshot().status());
        assertFalse(below.snapshot().triggered());

        JacobThresholdResult exact = detector.observe(evidence(FIRST, JacobCrop.WHEAT, 100L, 2L));
        assertEquals(JacobThresholdUpdate.ACCEPTED, exact.update());
        assertEquals(JacobThresholdStatus.TRIGGERED, exact.snapshot().status());
        assertTrue(exact.snapshot().triggered());
    }

    @Test
    void lowerCountsAndOlderSequencesCannotRegressTrustedState() {
        JacobThresholdDetector detector = new JacobThresholdDetector(
                JacobThresholds.of(JacobCrop.WHEAT, 100L));

        JacobThresholdSnapshot waiting = detector.observe(
                evidence(FIRST, JacobCrop.WHEAT, 90L, 10L)).snapshot();
        JacobThresholdResult lower = detector.observe(
                evidence(FIRST, JacobCrop.WHEAT, 80L, 11L));
        assertEquals(JacobThresholdUpdate.STALE, lower.update());
        assertEquals(waiting, lower.snapshot());

        JacobThresholdSnapshot triggered = detector.observe(
                evidence(FIRST, JacobCrop.WHEAT, 100L, 12L)).snapshot();
        JacobThresholdResult old = detector.observe(
                evidence(FIRST, JacobCrop.WHEAT, 99L, 11L));
        assertEquals(JacobThresholdUpdate.STALE, old.update());
        assertEquals(triggered, old.snapshot());

        JacobThresholdResult lowerAfterTrigger = detector.observe(
                evidence(FIRST, JacobCrop.WHEAT, 99L, 13L));
        assertEquals(JacobThresholdUpdate.STALE, lowerAfterTrigger.update());
        assertTrue(lowerAfterTrigger.snapshot().triggered());
        assertEquals(100L, lowerAfterTrigger.snapshot().evidence().get().collectedCount());
    }

    @Test
    void explicitContestIdentityResetsHighWaterMark() {
        JacobThresholdDetector detector = new JacobThresholdDetector(
                JacobThresholds.of(JacobCrop.WHEAT, 100L));

        detector.observe(evidence(FIRST, JacobCrop.WHEAT, 100L, 1L));
        JacobThresholdResult secondContest = detector.observe(
                evidence(SECOND, JacobCrop.WHEAT, 1L, 2L));

        assertEquals(JacobThresholdUpdate.ACCEPTED, secondContest.update());
        assertEquals(JacobThresholdStatus.WAITING, secondContest.snapshot().status());
        assertEquals(SECOND, secondContest.snapshot().contest().get());
        assertEquals(1L, secondContest.snapshot().evidence().get().collectedCount());
    }

    @Test
    void cropChangeWithinOneContestFailsClosedUntilIdentityChanges() {
        JacobThresholdDetector detector = new JacobThresholdDetector(
                new JacobThresholds(Map.of(JacobCrop.WHEAT, 100L, JacobCrop.CARROT, 100L)));

        detector.observe(evidence(FIRST, JacobCrop.WHEAT, 50L, 1L));
        JacobThresholdResult conflict = detector.observe(
                evidence(FIRST, JacobCrop.CARROT, 200L, 2L));
        assertEquals(JacobThresholdUpdate.REJECTED, conflict.update());
        assertEquals(JacobThresholdStatus.UNKNOWN, conflict.snapshot().status());
        assertEquals(JacobEvidenceIssue.CONFLICT, conflict.snapshot().issue());
        assertFalse(conflict.snapshot().triggered());
    }

    @Test
    void malformedAndUnknownEvidenceNeverTriggersAndCanRecoverWithFreshEvidence() {
        JacobThresholdDetector detector = new JacobThresholdDetector(
                JacobThresholds.of(JacobCrop.WHEAT, 100L));

        JacobEvidenceParseResult malformed = parser.parse(FIRST, 1L,
                List.of("Jacob's Contest", "Wheat 2m30s", "GOLD with 12,34"));
        JacobThresholdResult rejected = detector.observe(malformed);
        assertEquals(JacobThresholdUpdate.REJECTED, rejected.update());
        assertEquals(JacobThresholdStatus.UNKNOWN, rejected.snapshot().status());
        assertTrue(rejected.snapshot().failClosed());
        assertFalse(rejected.snapshot().triggered());

        JacobThresholdResult recovered = detector.observe(
                evidence(FIRST, JacobCrop.WHEAT, 100L, 2L));
        assertEquals(JacobThresholdStatus.TRIGGERED, recovered.snapshot().status());

        JacobThresholdResult unknown = detector.observe(parser.unknown(FIRST, 3L));
        assertEquals(JacobThresholdStatus.UNKNOWN, unknown.snapshot().status());
        assertFalse(unknown.snapshot().triggered());
    }

    @Test
    void missingThresholdFailsClosedAndThresholdMapsAreStable() {
        JacobThresholds thresholds = new JacobThresholds(Map.of(JacobCrop.WHEAT, 100L));
        assertThrows(UnsupportedOperationException.class,
                () -> thresholds.thresholds().put(JacobCrop.CARROT, 10L));

        JacobThresholdDetector detector = new JacobThresholdDetector(thresholds);
        JacobThresholdResult missing = detector.observe(
                evidence(FIRST, JacobCrop.CARROT, 10L, 1L));
        assertEquals(JacobThresholdUpdate.REJECTED, missing.update());
        assertEquals(JacobThresholdStatus.UNKNOWN, missing.snapshot().status());
        assertEquals(JacobEvidenceIssue.THRESHOLD_UNAVAILABLE, missing.snapshot().issue());
        assertFalse(missing.snapshot().triggered());
    }

    @Test
    void snapshotsAreStableValueObjects() {
        JacobContestEvidence evidence = new JacobContestEvidence(FIRST, JacobCrop.WHEAT, 100L, 7L);
        JacobThresholdDetector detector = new JacobThresholdDetector(
                JacobThresholds.of(JacobCrop.WHEAT, 100L));
        JacobThresholdSnapshot first = detector.observe(
                JacobEvidenceParseResult.present(evidence)).snapshot();
        JacobThresholdSnapshot second = detector.snapshot();

        assertEquals(first, second);
        assertEquals(Observation.present(evidence), first.evidence());
        assertEquals(100L, first.threshold().get());
        assertEquals(JacobThresholdStatus.TRIGGERED, first.status());
    }

    private static JacobEvidenceParseResult evidence(
            JacobContestIdentity contest,
            JacobCrop crop,
            long count,
            long sequence
    ) {
        return JacobEvidenceParseResult.present(
                new JacobContestEvidence(contest, crop, count, sequence));
    }
}

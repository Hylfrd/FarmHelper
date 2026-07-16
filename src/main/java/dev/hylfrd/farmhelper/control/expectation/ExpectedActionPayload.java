package dev.hylfrd.farmhelper.control.expectation;

/** Closed typed payload set consumed by later Failsafe detectors. */
public sealed interface ExpectedActionPayload
        permits ExpectedMotion, ExpectedRotation, ExpectedTeleport {
}

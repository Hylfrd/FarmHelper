package dev.hylfrd.farmhelper.control.rotation;

/** Receives exactly one terminal result for a successfully started rotation. */
@FunctionalInterface
public interface RotationCallback {
    void onTerminated(RotationResult result);
}

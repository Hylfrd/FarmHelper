package dev.hylfrd.farmhelper.feature.bps;

/** Observable outcome of offering one block-interaction signal to the BPS window. */
public enum BpsRecordResult {
    STOPPED,
    PAUSED,
    STALE_GENERATION,
    STALE_WORLD_EPOCH,
    NOT_BREAK_SUCCESS,
    BLOCK_STATE_UNAVAILABLE,
    CROP_MISMATCH,
    COUNTED
}

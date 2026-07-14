package dev.hylfrd.farmhelper.control.inventory;

/** Server menu state plus an adapter-owned content revision for same-state local changes. */
public record ScreenRevision(int menuStateId, long localContentRevision) {
    public ScreenRevision {
        if (menuStateId < 0) {
            throw new IllegalArgumentException("menuStateId must not be negative");
        }
        if (localContentRevision < 0L) {
            throw new IllegalArgumentException("localContentRevision must not be negative");
        }
    }

    public boolean advancedFrom(ScreenRevision earlier) {
        return earlier != null && !equals(earlier);
    }
}

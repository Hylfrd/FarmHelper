package dev.hylfrd.farmhelper.runtime.lifecycle;

/**
 * Generation fence for client-owned transient work.
 *
 * <p>Acquisition is allowed only while the current connection is known-ready and no lifecycle
 * cancellation fanout is in progress. A nested cancellation observes the existing generation and
 * never re-enters the fanout.</p>
 */
public final class ClientOwnershipFence {
    private boolean automationReady;
    private boolean cancelling;
    private long generation;

    public synchronized Boundary beginCancellation() {
        if (cancelling) {
            return new Boundary(generation, false);
        }
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("client ownership generation exhausted");
        }
        cancelling = true;
        generation++;
        return new Boundary(generation, true);
    }

    public synchronized void endCancellation(Boundary boundary) {
        if (!boundary.owner()) {
            return;
        }
        if (!cancelling || boundary.generation() != generation) {
            throw new IllegalStateException("stale client ownership boundary");
        }
        cancelling = false;
    }

    public synchronized void setAutomationReady(boolean automationReady) {
        this.automationReady = automationReady;
    }

    public synchronized boolean acquisitionAllowed() {
        return automationReady && !cancelling;
    }

    public synchronized boolean cancelling() {
        return cancelling;
    }

    public synchronized long generation() {
        return generation;
    }

    public void requireAcquisitionAllowed() {
        if (!acquisitionAllowed()) {
            throw new IllegalStateException("client transient ownership is fenced");
        }
    }

    public record Boundary(long generation, boolean owner) { }
}

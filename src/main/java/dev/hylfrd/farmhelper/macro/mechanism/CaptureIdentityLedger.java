package dev.hylfrd.farmhelper.macro.mechanism;

import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Generic exact capture identity ledger shared by macro-specific scan protocols. */
public final class CaptureIdentityLedger<K> {
    private Pending<K> pending;
    private long generation = 1L;
    private long phase = 1L;
    private long nextToken = 1L;

    public long generation() {
        return generation;
    }

    public long phase() {
        return phase;
    }

    public Optional<SpatialCaptureRequest> reusable(K key) {
        Objects.requireNonNull(key, "key");
        return pending != null && pending.key().equals(key)
                ? Optional.of(pending.request()) : Optional.empty();
    }

    public SpatialCaptureRequest begin(
            K key,
            long worldEpoch,
            BoxSnapshot bounds,
            Set<BlockPosition> blocks,
            BoxSnapshot playerBox
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(playerBox, "playerBox");
        long token = nextToken;
        nextToken = incrementPositive(nextToken);
        SpatialCaptureRequest request = new SpatialCaptureRequest(
                worldEpoch, token, Objects.requireNonNull(bounds, "bounds"),
                Objects.requireNonNull(blocks, "blocks"));
        pending = new Pending<>(key, playerBox, request);
        return request;
    }

    public boolean accepts(K key, SpatialSnapshot snapshot, BoxSnapshot playerBox) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(playerBox, "playerBox");
        return accepts(key, snapshot)
                && pending.key().equals(key)
                && pending.playerBox().equals(playerBox)
                && pending.playerBox().equals(snapshot.playerBox());
    }

    public boolean accepts(K key, SpatialSnapshot snapshot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(snapshot, "snapshot");
        return pending != null
                && pending.key().equals(key)
                && pending.request().worldEpoch() == snapshot.worldEpoch()
                && pending.request().requestToken() == snapshot.requestToken()
                && pending.request().bounds().equals(snapshot.bounds());
    }

    public void complete() {
        pending = null;
    }

    public void advancePhase() {
        phase = incrementPositive(phase);
        pending = null;
    }

    public void invalidate() {
        generation = incrementPositive(generation);
        phase = 1L;
        pending = null;
    }

    private static long incrementPositive(long value) {
        return value == Long.MAX_VALUE ? 1L : value + 1L;
    }

    private record Pending<K>(K key, BoxSnapshot playerBox, SpatialCaptureRequest request) {
        private Pending {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(playerBox, "playerBox");
            Objects.requireNonNull(request, "request");
        }
    }
}

package dev.hylfrd.farmhelper.macro;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotent nested Feature lease; a stale run can never resume its replacement. */
public final class FeatureSuspension implements AutoCloseable {
    private final String owner;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    FeatureSuspension(String owner, Runnable release) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.release = Objects.requireNonNull(release, "release");
    }

    public String owner() {
        return owner;
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release.run();
        }
    }
}

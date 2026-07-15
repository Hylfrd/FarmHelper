package dev.hylfrd.farmhelper.runtime.lifecycle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Best-effort cancellation of every transient runtime owner in a fixed order. */
public final class ClientCancellationFanout {
    private final List<Consumer<ClientCancellationReason>> participants;

    @SafeVarargs
    public ClientCancellationFanout(Consumer<ClientCancellationReason>... participants) {
        Objects.requireNonNull(participants, "participants");
        this.participants = List.of(participants.clone());
        if (this.participants.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("participant");
        }
    }

    /** Attempts every participant and returns one aggregate only after all attempts finish. */
    public Optional<RuntimeException> cancel(ClientCancellationReason reason) {
        Objects.requireNonNull(reason, "reason");
        RuntimeException aggregate = null;
        for (Consumer<ClientCancellationReason> participant : participants) {
            try {
                participant.accept(reason);
            } catch (RuntimeException | Error failure) {
                if (aggregate == null) {
                    aggregate = new RuntimeException("one or more client cancellation participants failed");
                }
                aggregate.addSuppressed(failure);
            }
        }
        return Optional.ofNullable(aggregate);
    }
}

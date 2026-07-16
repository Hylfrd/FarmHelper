package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.runtime.time.TaskOwner;

import java.util.Objects;

/** Canonical internal task identity derived from the public ticket without duplicate caller strings. */
public final class NavigationTaskOwner {
    private NavigationTaskOwner() {
    }

    public static TaskOwner from(NavigationTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        return new TaskOwner("navigation/" + ticket.owner().id()
                + "/" + ticket.generation() + "/world/" + ticket.worldEpoch());
    }
}

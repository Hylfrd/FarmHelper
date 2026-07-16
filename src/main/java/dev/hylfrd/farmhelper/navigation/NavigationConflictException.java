package dev.hylfrd.farmhelper.navigation;

/** Raised when a caller attempts an implicit replacement of the single active run. */
public final class NavigationConflictException extends IllegalStateException {
    private final NavigationTicket activeTicket;

    public NavigationConflictException(NavigationTicket activeTicket) {
        super("navigation already active for " + activeTicket.owner().id()
                + " generation " + activeTicket.generation());
        this.activeTicket = activeTicket;
    }

    public NavigationTicket activeTicket() {
        return activeTicket;
    }
}

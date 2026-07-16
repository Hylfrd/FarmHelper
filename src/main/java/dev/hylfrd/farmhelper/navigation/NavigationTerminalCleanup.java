package dev.hylfrd.farmhelper.navigation;

/** One idempotent owner-scoped cleanup participant invoked after terminal state is committed. */
@FunctionalInterface
public interface NavigationTerminalCleanup {
    void cleanup(NavigationTicket ticket, NavigationResult result);
}

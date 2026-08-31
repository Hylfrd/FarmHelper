package dev.hylfrd.farmhelper.feature.leave;

/**
 * Narrow action boundary for the two ordered side effects of leave-timer expiry.
 *
 * <p>The macro stop implementation must generation-fence the request. Implementations should make
 * both operations safe to retry when they throw, because the timer advances only after a call
 * returns successfully.</p>
 */
public interface LeaveTimerDisconnectPort {
    void stopMacro(long expectedMacroGeneration);

    void disconnect(String reason);
}

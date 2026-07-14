package dev.hylfrd.farmhelper.runtime.gamestate;

/** Public, fixed limits applied before game text is copied or parsed. */
public final class GameTextInputBudget {
    public static final int MAX_SCOREBOARD_LINES = 128;
    public static final int MAX_TAB_LINES = 256;
    public static final int MAX_TAB_FOOTER_LINES = 128;
    public static final int MAX_VACUUM_LORE_LINES = 64;
    public static final int MAX_CHAT_MESSAGES = 256;
    /** Measured in UTF-16 code units, matching {@link String#length()}. */
    public static final int MAX_LINE_CHARACTERS = 2_048;
    public static final long MAX_TOTAL_CHARACTERS = 65_536L;
    public static final int MAX_NUMERIC_TOKEN_CHARACTERS = 64;

    private GameTextInputBudget() {
    }
}

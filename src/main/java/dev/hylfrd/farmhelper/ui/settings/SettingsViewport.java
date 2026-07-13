package dev.hylfrd.farmhelper.ui.settings;

/** Clamped row-based scrolling model, safe for tiny windows and filtered lists. */
public final class SettingsViewport {
    private int firstRow;

    public int firstRow() {
        return firstRow;
    }

    public void scroll(int deltaRows, int rowCount, int visibleRows) {
        int maximum = Math.max(0, rowCount - Math.max(1, visibleRows));
        firstRow = Math.max(0, Math.min(maximum, firstRow + deltaRows));
    }

    public void clamp(int rowCount, int visibleRows) {
        scroll(0, rowCount, visibleRows);
    }

    public void reset() {
        firstRow = 0;
    }
}

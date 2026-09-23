package com.github.dhangofa.batteryremapper;

/**
 * The battery scale mapping, kept free of Android dependencies on purpose: the hooked System UI
 * process and the settings screen's live preview run this exact code, and it can therefore be
 * verified on a plain JVM.
 */
public final class Mapping {

    /**
     * The module's original hard-coded window: physical 20%..80% shows as 0%..100%.
     *
     * <p>Defined here so the row of code that does the mapping and the setting that configures it
     * cannot drift apart, and so this class stays free of anything Android.
     */
    public static final int DEFAULT_PHYSICAL_MIN = 20;
    public static final int DEFAULT_PHYSICAL_MAX = 80;

    private Mapping() {
    }

    /**
     * Maps a physical battery percentage onto the displayed one.
     *
     * <p>At or below {@code physicalMin} the device reports 0%, at or above {@code physicalMax} it
     * reports 100%, and the window in between is scaled linearly.
     *
     * <p>{@code remap(level, 20, 80)} reproduces the module's original hard-coded behaviour:
     * {@code max(0, round((level - 20) * 100 / 60))} clamped to 100.
     */
    public static int remap(int physicalLevel, int physicalMin, int physicalMax) {
        if (physicalMax <= physicalMin) {
            // Defensive: never divide by zero. Fall back to the default window.
            return remap(
                    physicalLevel,
                    DEFAULT_PHYSICAL_MIN,
                    DEFAULT_PHYSICAL_MAX
            );
        }

        if (physicalLevel <= physicalMin) {
            return 0;
        }

        if (physicalLevel >= physicalMax) {
            return 100;
        }

        return Math.round(
                (physicalLevel - physicalMin) * 100f / (physicalMax - physicalMin)
        );
    }

    /**
     * The inverse of {@link #remap}: the physical level that would display as
     * {@code displayedLevel} under this window.
     *
     * <p>Used to spell out what a displayed-level threshold means physically, which stops being
     * obvious as soon as the window is narrower than the full scale.
     */
    public static int physicalForDisplayed(
            int displayedLevel,
            int physicalMin,
            int physicalMax
    ) {
        if (physicalMax <= physicalMin) {
            // Defensive: never divide by zero. Fall back to the default window.
            return physicalForDisplayed(
                    displayedLevel,
                    DEFAULT_PHYSICAL_MIN,
                    DEFAULT_PHYSICAL_MAX
            );
        }

        int displayed = Math.max(0, Math.min(displayedLevel, 100));

        return Math.round(
                physicalMin + displayed * (physicalMax - physicalMin) / 100f
        );
    }
}

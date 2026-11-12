package cn.platejournal;

/** Pure timestamp logic; milliseconds are relative to the saved video. */
public final class Timeline {
    public static final long STEP_MS = 500;
    public static boolean near(long sampleMs, long playMs) {
        return near(sampleMs, playMs, STEP_MS);
    }
    public static boolean near(long sampleMs, long playMs, long intervalMs) {
        return Math.abs(sampleMs - playMs) <= Math.max(1, intervalMs) / 2;
    }
    public static String time(long ms) {
        long sec = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d.%01d", sec / 60, sec % 60, (ms % 1000) / 100);
    }
}

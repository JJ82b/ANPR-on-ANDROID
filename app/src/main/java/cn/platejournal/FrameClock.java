package cn.platejournal;

/** Relates camera sensor timestamps to the first frame sent to the video encoder. */
final class FrameClock {
    private long firstNs=-1;
    void reset(){firstNs=-1;}
    void firstFrame(long timestampNs){if(firstNs<0)firstNs=timestampNs;}
    long relativeMs(long timestampNs){return firstNs<0||timestampNs<firstNs?-1:(timestampNs-firstNs)/1_000_000L;}
}

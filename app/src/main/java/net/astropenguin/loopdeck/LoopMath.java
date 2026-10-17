package net.astropenguin.loopdeck;

/** Frame arithmetic is independent of Android and covered by a CLI test. */
final class LoopMath {
    static final long CACHE_BYTES = 32L * 1024 * 1024;
    static boolean valid(long a, long b) { return a >= 0 && b > a; }
    static long frame(long ms, int rate) { return ms * rate / 1000; }
    static long millis(long frame, int rate) { return frame * 1000 / rate; }
    static long position(long base, long played, long a, long b, boolean loop) {
        long absolute = base + played;
        return loop && valid(a, b) && absolute >= b ? a + Math.floorMod(absolute - a, b - a) : absolute;
    }
    static int cacheSamples(long frames) {
        return frames > 0 && frames <= CACHE_BYTES / (2 * Float.BYTES) ? (int)(frames * 2) : 0;
    }
    private LoopMath() {}
}

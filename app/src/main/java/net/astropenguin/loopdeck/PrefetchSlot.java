package net.astropenguin.loopdeck;

/** Exactly one owner closes each result, even when cancellation races preparation. */
final class PrefetchSlot<T extends AutoCloseable> {
    private boolean cancelled;
    private T value;
    synchronized boolean cancelled() { return cancelled; }
    synchronized void offer(T result) {
        if (cancelled) close(result);
        else value=result;
    }
    synchronized T take() { T result=value; value=null; return result; }
    synchronized void cancel() { cancelled=true; close(value); value=null; }
    private static void close(AutoCloseable value) {
        if (value!=null) try { value.close(); } catch (Exception ignored) { }
    }
}

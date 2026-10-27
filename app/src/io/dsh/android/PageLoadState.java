package io.dsh.android;

/** Main-thread page lifecycle: a late finish callback cannot conceal an error. */
final class PageLoadState {
    private enum Phase { IDLE, LOADING, READY, FAILED, DESTROYED }
    private Phase phase = Phase.IDLE;

    void begin() {
        if (phase != Phase.DESTROYED) phase = Phase.LOADING;
    }

    boolean complete() {
        if (phase != Phase.LOADING) return false;
        phase = Phase.READY;
        return true;
    }

    boolean fail() {
        if (phase == Phase.DESTROYED || phase == Phase.FAILED) return false;
        phase = Phase.FAILED;
        return true;
    }

    boolean isFailed() { return phase == Phase.FAILED; }
    boolean isReady() { return phase == Phase.READY; }
    void destroy() { phase = Phase.DESTROYED; }
}

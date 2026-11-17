package org.recorder.poco;

/** Used only on the acquisition thread. Tokens isolate asynchronous capture sessions. */
public final class Lifecycle {
    public enum State { IDLE, PREPARING, READY, RECORDING, FINALIZING, SAVED, INTERRUPTED, FAILED }
    public State state = State.IDLE;
    public long token;
    public long prepare() {
        if (state == State.RECORDING || state == State.FINALIZING) throw new IllegalStateException("Take busy");
        state = State.PREPARING; return ++token;
    }
    public boolean ready(long t) {
        if (t != token || state != State.PREPARING) return false;
        state = State.READY; return true;
    }
    public boolean start() {
        if (state != State.READY) return false;
        state = State.RECORDING; return true;
    }
    public boolean stop() {
        if (state != State.RECORDING) return false;
        state = State.FINALIZING; return true;
    }
    public void cancel() { token++; state = State.IDLE; }
}

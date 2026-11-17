package org.recorder.poco;

/** Constant-memory rate/gap statistics from measurement timestamps, not arrival times. */
public final class TimingStats {
    public long count, first, last, maxGap, invalid;
    public boolean add(long ns) {
        if (ns < 0 || (count > 0 && ns <= last)) { invalid++; return false; }
        if (count == 0) first = ns;
        else maxGap = Math.max(maxGap, ns - last);
        last = ns; count++; return true;
    }
    public double hz() { return count > 1 ? (count - 1) * 1e9 / (last - first) : 0; }
}

package org.recorder.poco;
import org.junit.Test;
import static org.junit.Assert.*;

public class TimingTests {
    @Test public void independentlyMeasuredRates() {
        TimingStats camera = new TimingStats(), imu = new TimingStats();
        for (int i=0;i<121;i++) camera.add(1_000_000_000L+i*25_000_000L/3);
        for (int i=0;i<201;i++) imu.add(1_000_000_000L+i*5_000_000L);
        assertEquals(120, camera.hz(), .001); assertEquals(200, imu.hz(), .001);
    }
    @Test public void badTimesDoNotCorruptAcceptedTimeline() {
        TimingStats s=new TimingStats(); assertTrue(s.add(0)); assertTrue(s.add(10));
        assertFalse(s.add(10)); assertFalse(s.add(9)); assertFalse(s.add(-1));
        assertEquals(2,s.count); assertEquals(10,s.last); assertEquals(3,s.invalid);
        assertTrue(s.add(40)); assertEquals(30,s.maxGap);
    }
    @Test public void noConcurrentStartsOrStops() {
        Lifecycle l=new Lifecycle(); long t=l.prepare(); assertFalse(l.start());
        assertTrue(l.ready(t)); assertTrue(l.start()); assertFalse(l.start());
        assertTrue(l.stop()); assertFalse(l.stop()); assertFalse(l.start());
    }
    @Test public void oldCameraCallbackCannotActivateNewSession() {
        Lifecycle l=new Lifecycle(); long old=l.prepare(); l.cancel(); long fresh=l.prepare();
        assertFalse(l.ready(old)); assertTrue(l.ready(fresh));
    }
    @Test(expected=IllegalStateException.class) public void settingsCannotChangeDuringFinalization() {
        Lifecycle l=new Lifecycle(); long t=l.prepare(); l.ready(t); l.start(); l.stop(); l.prepare();
    }
}

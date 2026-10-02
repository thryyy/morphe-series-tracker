package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

public class ContinueRequestTest {
    @Test
    public void deadlineAndNetworkCompletionCanLaunchOnlyOnce() {
        ContinueRequest r = new ContinueRequest();
        int token = r.begin();
        assertTrue(r.claim(token));
        assertFalse(r.claim(token));
        assertTrue(r.current(token));
    }

    @Test
    public void leavingOrStartingAnotherRequestInvalidatesEveryLateCallback() {
        ContinueRequest r = new ContinueRequest();
        int old = r.begin();
        r.cancel();
        assertFalse(r.claim(old));
        assertFalse(r.current(old));
        int current = r.begin();
        assertFalse(r.claim(old));
        assertTrue(r.claim(current));
        r.cancel();
        assertFalse(r.current(current));
    }
}

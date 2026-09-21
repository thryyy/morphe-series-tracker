package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecordingPolicyTest {
    @Test
    public void unknownAndIncognitoNeverRecordEvenWithConsent() {
        RecordingPolicy policy = new RecordingPolicy();
        policy.observe(null, false, true);
        assertFalse(policy.allows(null));
        assertFalse(policy.canConsent());
        policy.observe("account-a", true, true);
        assertFalse(policy.allows("account-a"));
        assertFalse(policy.canConsent());
    }

    @Test
    public void signedOutIdentityWorksWithoutAGoogleAccount() {
        RecordingPolicy policy = new RecordingPolicy();
        policy.observe("signed-out", false, false);
        assertTrue(policy.canConsent());
        assertFalse(policy.allows("signed-out"));
        policy.observe("signed-out", false, true);
        assertTrue(policy.allows("signed-out"));
    }

    @Test
    public void consentDoesNotFollowAccountSwitchesAndOldTicketsStayInvalid() {
        RecordingPolicy policy = new RecordingPolicy();
        policy.observe("account-a", false, true);
        long old = policy.generation();
        assertTrue(policy.allows("account-a"));
        policy.observe("account-b", false, true);
        assertFalse(policy.allows("account-a"));
        policy.observe("account-a", false, true);
        assertNotEquals(old, policy.generation());
    }

    @Test
    public void incognitoAndDisableTransitionsInvalidateQueuedObservations() {
        RecordingPolicy policy = new RecordingPolicy();
        policy.observe("a", false, true);
        long active = policy.generation();
        policy.observe("a", true, true);
        policy.observe("a", false, true);
        assertNotEquals(active, policy.generation());
        active = policy.generation();
        policy.observe("a", false, false);
        policy.observe("a", false, true);
        assertNotEquals(active, policy.generation());
        active = policy.generation();
        policy.observe("a", false, true);
        assertEquals(active, policy.generation());
    }

    @Test
    public void consentDialogCannotAuthorizeAnotherAccountAfterAsyncLoading() {
        RecordingPolicy policy = new RecordingPolicy();
        policy.observe("a", false, false);
        long shown = policy.generation();
        assertTrue(policy.canConsent(shown));
        policy.observe("b", false, false);
        assertFalse(policy.canConsent(shown));
        policy.observe("a", false, false);
        assertFalse(policy.canConsent(shown));
        long fresh = policy.generation();
        assertTrue(policy.canConsent(fresh));
        policy.observe("a", true, false);
        assertFalse(policy.canConsent(fresh));
    }
}

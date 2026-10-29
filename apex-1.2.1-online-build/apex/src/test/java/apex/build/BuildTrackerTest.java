package apex.build;

import apex.build.BuildTracker.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildTrackerTest {
    private BuildTracker t(int n) { var b = new BuildTracker(n); b.confirmTicks = 5; b.awaitTimeout = 20; b.maxAttempts = 3; return b; }

    @Test void startsAllPendingAndNotComplete() {
        var b = t(3);
        assertEquals(3, b.count(Status.PENDING));
        assertEquals(3, b.remaining());
        assertFalse(b.isFullyConfirmed());
        assertTrue(b.hasActionable());
    }

    @Test void acceptedInteractionAloneDoesNotCountAsPlaced() {
        var b = t(1);
        assertTrue(b.beginAttempt(0, 0));
        assertEquals(Status.AWAITING, b.status(0));
        assertEquals(0, b.confirmed());
        assertEquals(1, b.attemptedTotal);
    }

    @Test void confirmationNeedsTheWorldToMatchForConfirmTicks() {
        var b = t(1);
        b.beginAttempt(0, 0);
        for (int tick = 1; tick <= 4; tick++) { b.observe(0, View.MATCH, tick); assertEquals(Status.AWAITING, b.status(0), "tick " + tick); }
        b.observe(0, View.MATCH, 6);
        assertEquals(Status.CONFIRMED, b.status(0));
        assertTrue(b.isFullyConfirmed());
        assertFalse(b.hasActionable());
    }

    @Test void predictedBlockThatRevertsRestartsTheConfirmationWindow() {
        var b = t(1);
        b.beginAttempt(0, 0);
        b.observe(0, View.MATCH, 1);          // client-side prediction
        b.observe(0, View.EMPTY, 3);          // server rolled it back
        b.observe(0, View.MATCH, 4);
        b.observe(0, View.MATCH, 8);
        assertEquals(Status.AWAITING, b.status(0), "window restarted at tick 4, needs until tick 9");
        b.observe(0, View.MATCH, 9);
        assertEquals(Status.CONFIRMED, b.status(0));
    }

    @Test void timeoutCountsAsRejectedAndBacksOff() {
        var b = t(1);
        b.beginAttempt(0, 0);
        b.observe(0, View.EMPTY, 20);
        assertEquals(Status.PENDING, b.status(0));
        assertEquals(1, b.rejectedTotal);
        assertFalse(b.eligible(0, 21), "backoff");
        assertTrue(b.eligible(0, 24));
    }

    @Test void retriesAreBoundedThenFailWithAReason() {
        var b = t(1);
        int tick = 0;
        for (int a = 0; a < 3; a++) {
            while (!b.eligible(0, tick)) tick++;
            assertTrue(b.beginAttempt(0, tick));
            tick += 20;
            b.observe(0, View.EMPTY, tick);
        }
        assertEquals(Status.FAILED, b.status(0));
        assertEquals(Reason.REJECTED, b.reason(0));
        assertEquals(3, b.attempts(0));
        assertFalse(b.beginAttempt(0, tick), "no endless retries");
        assertFalse(b.hasActionable());
        assertFalse(b.isFullyConfirmed(), "never report completion with a failed block");
    }

    @Test void failedTargetIsReconsideredWhenConditionsChange() {
        var b = t(1);
        b.setSignature(1);
        b.fail(0, Reason.NO_SUPPORT, 0);
        assertEquals(Status.FAILED, b.status(0));
        assertEquals(0, b.reconsider(10), "same conditions, cooldown not elapsed");
        b.setSignature(2);                    // player moved / inventory changed / supports appeared
        assertEquals(1, b.reconsider(10));
        assertEquals(Status.PENDING, b.status(0));
        assertEquals(0, b.attempts(0), "fresh budget after a real change");
    }

    @Test void failedTargetGetsExactlyOneMoreTryAfterCooldownWithSameConditions() {
        var b = t(1);
        b.setSignature(7);
        b.fail(0, Reason.STATE_UNREACHABLE, 0);
        assertEquals(1, b.reconsider(200));
        assertEquals(Status.PENDING, b.status(0));
        b.beginAttempt(0, 200);
        b.observe(0, View.EMPTY, 220);
        assertEquals(Status.FAILED, b.status(0), "one try only");
    }

    @Test void aimTimeoutsFailOnlyAfterRepeatedMisses() {
        var b = t(1);
        b.aimTimeout(0, 1); b.aimTimeout(0, 2);
        assertEquals(Status.PENDING, b.status(0));
        b.aimTimeout(0, 3);
        assertEquals(Status.FAILED, b.status(0));
        assertEquals(Reason.NO_AIM, b.reason(0));
    }

    @Test void foreignBlockBlocksButIsRevisitedWhenCleared() {
        var b = t(1);
        b.observe(0, View.OCCUPIED, 1);
        assertEquals(Status.BLOCKED, b.status(0));
        assertEquals(Reason.OBSTRUCTED, b.reason(0));
        b.observe(0, View.EMPTY, 5);
        assertEquals(Status.PENDING, b.status(0));
    }

    @Test void wrongStateAfterPlacementBlocksInsteadOfCountingAsDone() {
        var b = t(1);
        b.beginAttempt(0, 0);
        b.observe(0, View.WRONG_STATE, 2);
        assertEquals(Status.AWAITING, b.status(0), "might still be a prediction");
        b.observe(0, View.WRONG_STATE, 5);
        assertEquals(Status.BLOCKED, b.status(0));
        assertEquals(Reason.WRONG_STATE, b.reason(0));
        assertEquals(0, b.confirmed());
    }

    @Test void blockAlreadyPresentIsConfirmedWithoutAnAttempt() {
        var b = t(1);
        b.observe(0, View.MATCH, 0);
        assertEquals(Status.CONFIRMED, b.status(0));
        assertEquals(0, b.attemptedTotal);
    }

    @Test void confirmedBlockThatIsLaterRemovedRegressesAndProgressDrops() {
        var b = t(2);
        b.observe(0, View.MATCH, 0); b.observe(1, View.MATCH, 0);
        assertTrue(b.isFullyConfirmed());
        b.observe(1, View.EMPTY, 50);
        assertEquals(1, b.confirmed());
        assertEquals(Status.PENDING, b.status(1));
        assertEquals(1, b.regressions);
        assertFalse(b.isFullyConfirmed());
    }

    @Test void unknownViewNeverConfirmsOrFails() {
        var b = t(1);
        b.beginAttempt(0, 0);
        b.observe(0, View.UNKNOWN, 30);
        assertEquals(Status.AWAITING, b.status(0));
        b.observe(0, View.UNKNOWN, 100);       // chunk unloaded for a long time: give up waiting, don't invent a result
        assertEquals(Status.PENDING, b.status(0));
        assertEquals(0, b.confirmed());
    }

    @Test void countsAlwaysSumToSizeAndCompletionNeedsEveryBlock() {
        var b = t(6);
        b.observe(0, View.MATCH, 0);
        b.observe(1, View.OCCUPIED, 0);
        b.beginAttempt(2, 0);
        b.fail(3, Reason.NO_SUPPORT, 0);
        int sum = 0;
        for (Status s : Status.values()) sum += b.count(s);
        assertEquals(6, sum);
        assertEquals(5, b.remaining());
        assertEquals(1, b.reasons().get(Reason.OBSTRUCTED));
        assertEquals(1, b.reasons().get(Reason.NO_SUPPORT));
    }

    @Test void forceRetryResetsFailedAndBlocked() {
        var b = t(2);
        b.fail(0, Reason.NO_SUPPORT, 0);
        b.observe(1, View.OCCUPIED, 0);
        assertEquals(2, b.forceRetry(5));
        assertEquals(2, b.count(Status.PENDING));
    }

    @Test void emptySchematicIsTriviallyCompleteButNotActionable() {
        var b = t(0);
        assertTrue(b.isFullyConfirmed());
        assertFalse(b.hasActionable());
    }
}

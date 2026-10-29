package apex.build;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-target build state machine. Pure Java: all world knowledge arrives through observe(View) so
 * placement confirmation, retries and progress accounting can be unit-tested.
 *
 * Lifecycle: PENDING -> (beginAttempt) AWAITING -> CONFIRMED, once the world has matched for confirmTicks
 * (client-side prediction means "matches right after clicking" proves nothing).
 * AWAITING that never matches within awaitTimeout counts as a rejected attempt: retried with backoff, then FAILED
 * after maxAttempts. FAILED and BLOCKED targets are reconsidered when conditions change (signature) or after a cooldown.
 */
public final class BuildTracker {
    public enum Status { PENDING, AWAITING, CONFIRMED, BLOCKED, FAILED }
    public enum Reason { NONE, REJECTED, NO_SUPPORT, STATE_UNREACHABLE, NO_AIM, WRONG_STATE, OBSTRUCTED }
    /** What the world currently shows at a target position relative to what we want. */
    public enum View { MATCH, EMPTY, WRONG_STATE, OCCUPIED, UNKNOWN }

    public int confirmTicks = 6, awaitTimeout = 30, maxAttempts = 3, cooldownTicks = 200, maxAimFails = 3;

    private final int n;
    private final byte[] status, reason, attempts, aimFails;
    private final int[] issued, matchSince, retryAt, failTick;
    private final long[] failSig;
    private final int[] counts = new int[Status.values().length];
    private long signature;
    public long attemptedTotal, rejectedTotal, regressions;

    public BuildTracker(int size) {
        n = size;
        status = new byte[n]; reason = new byte[n]; attempts = new byte[n]; aimFails = new byte[n];
        issued = new int[n]; matchSince = new int[n]; retryAt = new int[n]; failTick = new int[n]; failSig = new long[n];
        java.util.Arrays.fill(matchSince, -1);
        counts[Status.PENDING.ordinal()] = n;
    }

    public int size() { return n; }
    public Status status(int i) { return Status.values()[status[i]]; }
    public Reason reason(int i) { return Reason.values()[reason[i]]; }
    public int attempts(int i) { return attempts[i]; }
    public int count(Status s) { return counts[s.ordinal()]; }
    public int confirmed() { return count(Status.CONFIRMED); }
    public int remaining() { return n - confirmed(); }
    public boolean hasActionable() { return count(Status.PENDING) + count(Status.AWAITING) > 0; }
    public boolean isFullyConfirmed() { return confirmed() == n; }
    public void setSignature(long sig) { signature = sig; }
    public long signature() { return signature; }

    private void set(int i, Status s, Reason r) {
        counts[status[i]]--;
        counts[s.ordinal()]++;
        status[i] = (byte) s.ordinal();
        reason[i] = (byte) r.ordinal();
    }

    public boolean eligible(int i, int tick) { return status(i) == Status.PENDING && tick >= retryAt[i]; }

    /** Call right before sending the interaction. */
    public boolean beginAttempt(int i, int tick) {
        if (status(i) != Status.PENDING) return false;
        set(i, Status.AWAITING, Reason.NONE);
        if (attempts[i] < 100) attempts[i]++;
        attemptedTotal++;
        issued[i] = tick;
        matchSince[i] = -1;
        return true;
    }

    /** The interaction itself was refused client-side (not accepted). */
    public void rejectNow(int i, int tick) { if (status(i) == Status.AWAITING) attemptFailed(i, tick); }

    private void attemptFailed(int i, int tick) {
        rejectedTotal++;
        matchSince[i] = -1;
        if (attempts[i] >= maxAttempts) {
            set(i, Status.FAILED, Reason.REJECTED);
            failTick[i] = tick; failSig[i] = signature;
        } else {
            set(i, Status.PENDING, Reason.REJECTED);
            retryAt[i] = tick + Math.min(40, 4 << (attempts[i] - 1));
        }
    }

    /** Evaluation proved no placement is possible under current conditions (no support / state unreachable). */
    public void fail(int i, Reason r, int tick) {
        if (status(i) != Status.PENDING) return;
        set(i, Status.FAILED, r);
        failTick[i] = tick; failSig[i] = signature;
    }

    /** Aiming at a candidate timed out (no interaction was sent). After maxAimFails the target fails with NO_AIM. */
    public void aimTimeout(int i, int tick) {
        if (aimFails[i] < 100) aimFails[i]++;
        if (aimFails[i] >= maxAimFails) fail(i, Reason.NO_AIM, tick);
    }

    public void observe(int i, View v, int tick) {
        switch (status(i)) {
            case PENDING -> {
                switch (v) {
                    case MATCH -> set(i, Status.CONFIRMED, Reason.NONE);
                    case OCCUPIED -> { set(i, Status.BLOCKED, Reason.OBSTRUCTED); failTick[i] = tick; failSig[i] = signature; }
                    case WRONG_STATE -> { set(i, Status.BLOCKED, Reason.WRONG_STATE); failTick[i] = tick; failSig[i] = signature; }
                    default -> { }
                }
            }
            case AWAITING -> {
                switch (v) {
                    case MATCH -> {
                        if (matchSince[i] < 0) matchSince[i] = tick;
                        if (tick - matchSince[i] >= confirmTicks) set(i, Status.CONFIRMED, Reason.NONE);
                    }
                    case EMPTY -> {
                        matchSince[i] = -1;
                        if (tick - issued[i] >= awaitTimeout) attemptFailed(i, tick);
                    }
                    case WRONG_STATE, OCCUPIED -> {
                        matchSince[i] = -1;
                        if (tick - issued[i] >= confirmTicks) {
                            set(i, Status.BLOCKED, v == View.WRONG_STATE ? Reason.WRONG_STATE : Reason.OBSTRUCTED);
                            failTick[i] = tick; failSig[i] = signature;
                        }
                    }
                    case UNKNOWN -> { if (tick - issued[i] >= awaitTimeout * 4) set(i, Status.PENDING, Reason.NONE); }
                }
            }
            case CONFIRMED -> {
                switch (v) {
                    case EMPTY -> { regress(i, tick); set(i, Status.PENDING, Reason.NONE); }
                    case OCCUPIED -> { regress(i, tick); set(i, Status.BLOCKED, Reason.OBSTRUCTED); failTick[i] = tick; failSig[i] = signature; }
                    case WRONG_STATE -> { regress(i, tick); set(i, Status.BLOCKED, Reason.WRONG_STATE); failTick[i] = tick; failSig[i] = signature; }
                    default -> { }
                }
            }
            case BLOCKED -> {
                if (v == View.MATCH) set(i, Status.CONFIRMED, Reason.NONE);
                else if (v == View.EMPTY) { attempts[i] = 0; retryAt[i] = tick; set(i, Status.PENDING, Reason.NONE); }
            }
            case FAILED -> { if (v == View.MATCH) set(i, Status.CONFIRMED, Reason.NONE); }
        }
    }

    private void regress(int i, int tick) { regressions++; attempts[i] = 0; aimFails[i] = 0; retryAt[i] = tick; }

    /** Give FAILED targets another go when conditions changed or the cooldown elapsed. Returns how many were reset. */
    public int reconsider(int tick) {
        int reset = 0;
        for (int i = 0; i < n; i++) {
            if (status(i) != Status.FAILED) continue;
            boolean changed = failSig[i] != signature;
            if (!changed && tick - failTick[i] < cooldownTicks) continue;
            attempts[i] = (byte) (changed ? 0 : Math.max(0, maxAttempts - 1));   // same conditions: exactly one more try
            aimFails[i] = 0; retryAt[i] = tick;
            set(i, Status.PENDING, Reason.NONE);
            reset++;
        }
        return reset;
    }

    /** User asked to retry everything that isn't confirmed. */
    public int forceRetry(int tick) {
        int reset = 0;
        for (int i = 0; i < n; i++) {
            Status s = status(i);
            if (s != Status.FAILED && s != Status.BLOCKED) continue;
            attempts[i] = 0; aimFails[i] = 0; retryAt[i] = tick;
            set(i, Status.PENDING, Reason.NONE);
            reset++;
        }
        return reset;
    }

    /** Counts of FAILED/BLOCKED targets by reason, for diagnostics. */
    public Map<Reason, Integer> reasons() {
        Map<Reason, Integer> m = new EnumMap<>(Reason.class);
        for (int i = 0; i < n; i++) {
            Status s = status(i);
            if (s == Status.FAILED || s == Status.BLOCKED) m.merge(reason(i), 1, Integer::sum);
        }
        return m;
    }
}

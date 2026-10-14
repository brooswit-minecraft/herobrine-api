package dev.herobrine.api.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks long-running actions with ownership, timeouts, locks and bounded history.
 * Mutating methods are game-thread only; readers may be on any thread.
 */
public final class Operations {
    public enum State { RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT }

    public static final class Op {
        public final String id, action, owner;
        public final long createdAtMillis;
        public final List<String> locks;
        public volatile State state = State.RUNNING;
        public volatile Object result, progress;
        public volatile String error;
        public volatile long finishedAtMillis;
        final LongRunning task;
        final long deadlineNanos; // 0 = none

        Op(String id, String action, String owner, long timeoutMs, List<String> locks, LongRunning task) {
            this.id = id; this.action = action; this.owner = owner; this.locks = locks; this.task = task;
            this.createdAtMillis = System.currentTimeMillis();
            this.deadlineNanos = timeoutMs > 0 ? System.nanoTime() + timeoutMs * 1_000_000L : 0;
        }
    }

    private static final int MAX_HISTORY = 500;
    private final Map<String, Op> ops = new ConcurrentHashMap<>();
    private final Map<String, Op> lockHolders = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    /**
     * Throws RESOURCE_LOCKED if another owner holds any of the locks.
     * Same-owner conflicts are resolved by the caller (see {@link #start}).
     */
    public void checkLocks(List<String> locks, String owner) {
        for (String l : locks) {
            Op h = lockHolders.get(l);
            if (h != null && h.state == State.RUNNING && !h.owner.equals(owner))
                throw new ApiException(409, "RESOURCE_LOCKED",
                        "'" + l + "' is held by another controller (operation " + h.id + ")");
        }
    }

    /** Game thread only. A new operation by the same owner preempts that owner's earlier holder of a lock. */
    public Op start(String action, String owner, long timeoutMs, List<String> locks, LongRunning task) {
        checkLocks(locks, owner);
        for (String l : locks) {
            Op h = lockHolders.get(l);
            if (h != null && h.state == State.RUNNING) finish(h, State.CANCELLED, null, "Preempted by " + action, true);
        }
        Op op = new Op("op-" + counter.incrementAndGet(), action, owner, timeoutMs, locks, task);
        ops.put(op.id, op);
        for (String l : locks) lockHolders.put(l, op);
        trim();
        return op;
    }

    public Op get(String id) { return ops.get(id); }

    /** Newest first. */
    public List<Op> list() {
        List<Op> l = new ArrayList<>(ops.values());
        l.sort(Comparator.comparingLong((Op o) -> o.createdAtMillis).reversed());
        return l;
    }

    /** Game thread only; call once per client tick. */
    public void tick() {
        for (Op op : ops.values()) {
            if (op.state != State.RUNNING) continue;
            if (op.deadlineNanos != 0 && System.nanoTime() > op.deadlineNanos) {
                finish(op, State.TIMED_OUT, null, "Operation timed out", true);
                continue;
            }
            try {
                Object r = op.task.tick();
                op.progress = op.task.progress();
                if (r != null) finish(op, State.COMPLETED, r, null, false);
            } catch (RuntimeException e) {
                finish(op, State.FAILED, null, String.valueOf(e.getMessage()), true);
            }
        }
    }

    /** Game thread only. */
    public boolean cancel(String id) {
        Op op = ops.get(id);
        if (op == null || op.state != State.RUNNING) return false;
        finish(op, State.CANCELLED, null, null, true);
        return true;
    }

    private void finish(Op op, State s, Object result, String error, boolean cancelTask) {
        if (cancelTask) {
            try { op.task.cancel(); } catch (RuntimeException ignored) {}
        }
        op.result = result;
        op.error = error;
        op.finishedAtMillis = System.currentTimeMillis();
        op.state = s;
        for (String l : op.locks) lockHolders.remove(l, op);
    }

    private void trim() {
        if (ops.size() <= MAX_HISTORY) return;
        List<Op> finished = new ArrayList<>();
        for (Op o : ops.values()) if (o.state != State.RUNNING) finished.add(o);
        finished.sort(Comparator.comparingLong(o -> o.createdAtMillis));
        for (int i = 0; i < finished.size() && ops.size() > MAX_HISTORY; i++) ops.remove(finished.get(i).id);
    }
}

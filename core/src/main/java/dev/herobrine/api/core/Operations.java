package dev.herobrine.api.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks long-running actions. {@link #tick()} must be called once per client tick on the game thread. */
public final class Operations {
    public enum State { RUNNING, COMPLETED, FAILED, CANCELLED }

    public static final class Op {
        public final String id;
        public final String action;
        public volatile State state = State.RUNNING;
        public volatile Object result;
        public volatile String error;
        final LongRunning task;
        Op(String id, String action, LongRunning task) { this.id = id; this.action = action; this.task = task; }
    }

    private final Map<String, Op> ops = new ConcurrentHashMap<>();
    private long counter;

    /** Game thread only. */
    public Op start(String action, LongRunning task) {
        Op op = new Op("op-" + (++counter), action, task);
        ops.put(op.id, op);
        return op;
    }

    public Op get(String id) { return ops.get(id); }

    /** Game thread only. */
    public void tick() {
        for (Op op : ops.values()) {
            if (op.state != State.RUNNING) continue;
            try {
                Object r = op.task.tick();
                if (r != null) { op.result = r; op.state = State.COMPLETED; }
            } catch (RuntimeException e) {
                op.error = String.valueOf(e.getMessage());
                op.state = State.FAILED;
            }
        }
    }

    /** Game thread only. */
    public boolean cancel(String id) {
        Op op = ops.get(id);
        if (op == null || op.state != State.RUNNING) return false;
        op.task.cancel();
        op.state = State.CANCELLED;
        return true;
    }
}

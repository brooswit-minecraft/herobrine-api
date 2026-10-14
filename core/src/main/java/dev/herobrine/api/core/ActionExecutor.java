package dev.herobrine.api.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Runs actions: immediate, delayed, or as part of a sequence. All methods are game-thread only. */
public final class ActionExecutor {
    public record Outcome(Object result, Stage stage, Operations.Op op) {}
    public record Step(ActionDef action, Params params, int delayTicks) {}

    private final ApiContext ctx;

    ActionExecutor(ApiContext ctx) { this.ctx = ctx; }

    /** Refuses control actions while the platform says control is blocked (e.g. on multiplayer servers). */
    private void checkControl(ActionDef a) {
        if (!a.permission().startsWith("control.")) return;
        String reason = ctx.controlBlock.get();
        if (reason != null) throw ApiException.unavailable(reason);
    }

    public Outcome run(ActionDef a, Params p, String owner, int delayTicks, long timeoutMs) {
        checkControl(a);
        ctx.operations.checkLocks(a.locks(), owner);
        if (delayTicks > 0) {
            Operations.Op op = ctx.operations.start(a.name(), owner, timeoutMs, a.locks(), new Deferred(delayTicks, () -> { checkControl(a); return a.handler().apply(p); }));
            return new Outcome(null, Stage.ACCEPTED, op);
        }
        Object r = a.handler().apply(p);
        if (r instanceof LongRunning lr) {
            return new Outcome(null, Stage.ACCEPTED, ctx.operations.start(a.name(), owner, timeoutMs, a.locks(), lr));
        }
        if (r instanceof Staged s) return new Outcome(s.result(), s.stage(), null);
        return new Outcome(r, Stage.EXECUTED_LOCALLY, null);
    }

    public Operations.Op runSequence(List<Step> steps, String owner, long timeoutMs, boolean stopOnError) {
        return ctx.operations.start("sequence", owner, timeoutMs, List.of(), new Sequence(steps, owner, stopOnError));
    }

    static Object unwrap(Object r) {
        if (r instanceof Staged s) r = s.result();
        return r == null ? java.util.Map.of() : r;
    }

    /** Waits N ticks, then runs the handler; follows it if it is itself long-running. */
    private static final class Deferred implements LongRunning {
        private int remaining;
        private final Supplier<Object> body;
        private LongRunning inner;

        Deferred(int ticks, Supplier<Object> body) { this.remaining = ticks; this.body = body; }

        public Object tick() {
            if (remaining-- > 0) return null;
            if (inner == null) {
                Object r = body.get();
                if (!(r instanceof LongRunning lr)) return unwrap(r);
                inner = lr;
            }
            return inner.tick();
        }
        public void cancel() { if (inner != null) inner.cancel(); }
        public Object progress() { return inner != null ? inner.progress() : java.util.Map.of("delayTicksRemaining", Math.max(0, remaining)); }
    }

    /** Runs steps one after another, each waiting for the previous to finish. */
    private final class Sequence implements LongRunning {
        private final List<Step> steps;
        private final String owner;
        private final boolean stopOnError;
        private final List<Object> results = new ArrayList<>();
        private int idx;
        private int delay = -1;
        private LongRunning current;

        Sequence(List<Step> steps, String owner, boolean stopOnError) { this.steps = steps; this.owner = owner; this.stopOnError = stopOnError; }

        public Object tick() {
            try {
                if (current == null) {
                    if (idx >= steps.size()) return java.util.Map.of("results", results);
                    Step s = steps.get(idx);
                    if (delay < 0) delay = s.delayTicks();
                    if (delay-- > 0) return null;
                    delay = -1;
                    checkControl(s.action());
                    ctx.operations.checkLocks(s.action().locks(), owner);
                    Object r = s.action().handler().apply(s.params());
                    if (r instanceof LongRunning lr) { current = lr; return null; }
                    results.add(java.util.Map.of("action", s.action().name(), "success", true, "result", unwrap(r)));
                    idx++;
                    return null;
                }
                Object v = current.tick();
                if (v == null) return null;
                results.add(java.util.Map.of("action", steps.get(idx).action().name(), "success", true, "result", unwrap(v)));
                current = null;
                idx++;
                return null;
            } catch (RuntimeException e) {
                if (stopOnError) throw e;
                results.add(java.util.Map.of("action", steps.get(idx).action().name(), "success", false, "error", String.valueOf(e.getMessage())));
                current = null;
                idx++;
                return null;
            }
        }
        public void cancel() { if (current != null) current.cancel(); }
        public Object progress() { return java.util.Map.of("step", idx, "total", steps.size()); }
    }
}

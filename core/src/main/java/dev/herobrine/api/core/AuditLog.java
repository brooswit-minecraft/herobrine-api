package dev.herobrine.api.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Bounded record of control actions and admin changes (spec 12.4). */
public final class AuditLog {
    public record Entry(long timeMillis, String owner, String kind, String detail) {}

    private final ArrayDeque<Entry> ring = new ArrayDeque<>();
    private volatile Consumer<Entry> sink = e -> {};

    public void sink(Consumer<Entry> s) { sink = s; }

    public void record(String owner, String kind, String detail) {
        Entry e = new Entry(System.currentTimeMillis(), owner, kind, detail);
        synchronized (ring) {
            if (ring.size() >= 1000) ring.removeFirst();
            ring.addLast(e);
        }
        sink.accept(e);
    }

    public List<Entry> recent() {
        synchronized (ring) { return new ArrayList<>(ring); }
    }
}

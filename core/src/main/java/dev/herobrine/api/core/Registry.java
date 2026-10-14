package dev.herobrine.api.core;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe name -> definition registry. Other mods register their own entries at runtime. */
public final class Registry<T> {
    private final Map<String, T> map = new ConcurrentHashMap<>();
    private final String kind;

    public Registry(String kind) { this.kind = kind; }

    public void register(String id, T def) {
        if (map.putIfAbsent(id, def) != null) throw new IllegalStateException("Duplicate " + kind + ": " + id);
    }
    public T get(String id) { return map.get(id); }
    public Collection<T> all() { return map.values(); }
}

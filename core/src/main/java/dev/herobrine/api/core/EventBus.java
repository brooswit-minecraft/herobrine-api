package dev.herobrine.api.core;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/** Fan-out of named events to subscribers (currently SSE). Safe to publish from any thread. */
public final class EventBus {
    public record Event(String type, long timeMillis, JsonObject data) {}

    private final List<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();
    private final Set<String> knownTypes = new CopyOnWriteArraySet<>();

    public void declare(String type) { knownTypes.add(type); }
    public Set<String> knownTypes() { return knownTypes; }

    public Runnable subscribe(Consumer<Event> c) {
        subscribers.add(c);
        return () -> subscribers.remove(c);
    }

    public void publish(String type, JsonObject data) {
        knownTypes.add(type);
        Event e = new Event(type, System.currentTimeMillis(), data);
        for (Consumer<Event> c : subscribers) {
            try { c.accept(e); } catch (RuntimeException ignored) { subscribers.remove(c); }
        }
    }
}

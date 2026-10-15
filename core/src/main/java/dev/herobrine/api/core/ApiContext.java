package dev.herobrine.api.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Everything the server needs. Platform modules populate the registries. Holds no request state. */
public final class ApiContext {
    public final Registry<ActionDef> actions = new Registry<>("action");
    public final Registry<ResourceDef> resources = new Registry<>("resource");
    public final EventBus events = new EventBus();
    public final GameThread gameThread;
    /** Supplies the /mods payload; set by the platform module. */
    public volatile Supplier<Object> mods = () -> java.util.List.of();
    /** Current game tick for the "observedTick" field; -1 when unknown. */
    public volatile LongSupplier observedTick = () -> -1;
    public volatile BooleanSupplier serverCompanion = () -> false;
    public volatile String platform = "unknown";
    /** Where executed actions are logged (the game log). */
    public volatile Consumer<String> log = s -> {};
    /**
     * Returns a human-readable reason while actions must be refused (e.g. on multiplayer servers),
     * or null when they are allowed. Evaluated on the game thread every time an action runs.
     */
    public volatile Supplier<String> controlBlock = () -> null;

    private final Map<String, BooleanSupplier> capabilityFlags = new ConcurrentHashMap<>();
    private final Map<String, BooleanSupplier> integrations = new ConcurrentHashMap<>();

    public ApiContext(GameThread gameThread) { this.gameThread = gameThread; }

    /** e.g. "world.unloadedChunks" -> whether it is currently obtainable. Evaluated on the game thread. */
    public void capability(String name, BooleanSupplier available) { capabilityFlags.put(name, available); }
    public void integration(String name, BooleanSupplier installed) { integrations.put(name, installed); }

    /** Capabilities payload. Call on the game thread. */
    public Map<String, Object> capabilitiesDocument() {
        Map<String, Object> caps = new java.util.TreeMap<>(), ints = new java.util.TreeMap<>();
        capabilityFlags.forEach((k, v) -> caps.put(k, v.getAsBoolean()));
        integrations.forEach((k, v) -> ints.put(k, v.getAsBoolean()));
        return Map.of("client", true, "serverCompanion", serverCompanion.getAsBoolean(),
                "integrations", ints, "capabilities", caps, "platform", platform);
    }

    /** Runs a provider; one failing provider must not prevent others from registering. */
    public boolean install(ResourceProvider provider) {
        try {
            provider.register(new ApiRegistrar(this, provider.modId()));
            return true;
        } catch (RuntimeException e) {
            System.err.println("[herobrine-api] provider '" + provider.modId() + "' failed: " + e);
            return false;
        }
    }
}

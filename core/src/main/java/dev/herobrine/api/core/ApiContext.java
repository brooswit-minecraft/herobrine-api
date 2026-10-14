package dev.herobrine.api.core;

import com.google.gson.JsonObject;
import java.util.function.Supplier;

/** Everything the server needs. Platform modules populate the registries. */
public final class ApiContext {
    public final Registry<ActionDef> actions = new Registry<>("action");
    public final Registry<ResourceDef> resources = new Registry<>("resource");
    public final Operations operations = new Operations();
    public final EventBus events = new EventBus();
    public final GameThread gameThread;
    /** Supplies the /mods and /capabilities payloads; set by the platform module. */
    public volatile Supplier<Object> mods = () -> new JsonObject();
    public volatile Supplier<Object> capabilities = () -> new JsonObject();
    public volatile String platform = "unknown";

    public ApiContext(GameThread gameThread) { this.gameThread = gameThread; }
}

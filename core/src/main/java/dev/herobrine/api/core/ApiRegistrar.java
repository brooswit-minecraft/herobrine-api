package dev.herobrine.api.core;

import java.util.function.Function;

/**
 * Namespaced registration handle given to a {@link ResourceProvider}. Prefixes every id with the
 * provider's mod id so third parties cannot shadow built-in or other mods' entries.
 */
public final class ApiRegistrar {
    private final ApiContext ctx;
    private final String modId;

    public ApiRegistrar(ApiContext ctx, String modId) {
        if (!modId.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid mod id: " + modId);
        this.ctx = ctx;
        this.modId = modId;
    }

    public String modId() { return modId; }

    public void resource(String name, String description, Availability availability, Function<Params, Object> supplier) {
        resource(name, description, availability, "read.client", supplier);
    }

    public void resource(String name, String description, Availability availability, String permission, Function<Params, Object> supplier) {
        String id = modId + "." + name;
        ctx.resources.register(id, new ResourceDef(id, description, availability, permission, supplier));
    }

    /** Builder-based action registration; the action name is prefixed automatically. */
    public ActionDef.Builder action(String name, String description) {
        return ActionDef.builder(modId + "." + name, description).permission("control.mods");
    }

    public void register(ActionDef def) { ctx.actions.register(def.name(), def); }

    public void event(String type) { ctx.events.declare(modId + "." + type); }
}

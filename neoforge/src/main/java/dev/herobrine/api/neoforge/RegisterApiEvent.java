package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.ResourceProvider;
import net.neoforged.bus.api.Event;

/**
 * Posted on {@code NeoForge.EVENT_BUS} during client setup. Other mods subscribe to it and call
 * {@link #install} to expose their own resources and actions; ids are namespaced by the provider's mod id.
 */
public final class RegisterApiEvent extends Event {
    private final ApiContext ctx;

    RegisterApiEvent(ApiContext ctx) { this.ctx = ctx; }

    public boolean install(ResourceProvider provider) { return ctx.install(provider); }
}

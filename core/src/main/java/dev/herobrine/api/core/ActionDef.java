package dev.herobrine.api.core;

import java.util.List;
import java.util.function.Function;

/**
 * A callable action. The handler runs on the game thread, does the thing, and returns what the
 * underlying call returned. Actions never block waiting for the game: anything that takes time is
 * started here and observed through resources and events.
 */
public record ActionDef(
        String name,
        String description,
        List<ParamDef> params,
        Availability availability,
        Function<Params, Object> handler) {

    public static Builder builder(String name, String description) { return new Builder(name, description); }

    public static final class Builder {
        private final String name, description;
        private final java.util.ArrayList<ParamDef> params = new java.util.ArrayList<>();
        private Availability availability = Availability.LOCAL;

        private Builder(String n, String d) { name = n; description = d; }

        public Builder param(ParamDef p) { params.add(p); return this; }
        public Builder availability(Availability a) { availability = a; return this; }
        public ActionDef handler(Function<Params, Object> h) {
            return new ActionDef(name, description, List.copyOf(params), availability, h);
        }
    }
}

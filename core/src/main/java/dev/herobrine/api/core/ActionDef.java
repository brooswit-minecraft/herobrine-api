package dev.herobrine.api.core;

import java.util.List;
import java.util.function.Function;

/**
 * A callable action. The handler runs on the game thread. If it returns a {@link LongRunning}
 * the call becomes an operation that can be polled and cancelled. {@code locks} name exclusive
 * resources (e.g. "player.movement"); a caller cannot run the action while another owner holds one.
 */
public record ActionDef(
        String name,
        String description,
        List<ParamDef> params,
        Availability availability,
        String permission,
        List<String> locks,
        Function<Params, Object> handler) {

    public static Builder builder(String name, String description) { return new Builder(name, description); }

    public static final class Builder {
        private final String name, description;
        private final java.util.ArrayList<ParamDef> params = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> locks = new java.util.ArrayList<>();
        private Availability availability = Availability.LOCAL;
        private String permission = "control.player";

        private Builder(String n, String d) { name = n; description = d; }

        public Builder param(ParamDef p) { params.add(p); return this; }
        public Builder availability(Availability a) { availability = a; return this; }
        public Builder permission(String p) { permission = p; return this; }
        public Builder lock(String l) { locks.add(l); return this; }
        public ActionDef handler(Function<Params, Object> h) {
            return new ActionDef(name, description, List.copyOf(params), availability, permission, List.copyOf(locks), h);
        }
    }
}

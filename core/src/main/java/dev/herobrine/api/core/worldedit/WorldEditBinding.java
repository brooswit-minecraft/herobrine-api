package dev.herobrine.api.core.worldedit;

import dev.herobrine.api.core.ActionDef;
import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.ApiException;
import dev.herobrine.api.core.Availability;
import dev.herobrine.api.core.ParamDef;
import dev.herobrine.api.core.Params;
import dev.herobrine.api.core.Reason;
import dev.herobrine.api.core.ResourceDef;
import dev.herobrine.api.core.Unavailable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Registers every {@link WorldEditSchema#OPERATIONS} entry with the API (MINECRAFT-476). Operations that
 * only report state ({@link WorldEditActions.Kind#READ}, the selection reporters) become resources, readable
 * with the read-only token; everything else becomes an action, which {@code ApiServer} runs only for the
 * full-control token and only while {@code controlBlock} allows actions. All of them stay registered and
 * discoverable when WorldEdit is absent: actions then fail with 409 ACTION_UNAVAILABLE and resources report
 * {@code MOD_NOT_INSTALLED}, the same as the Baritone bindings.
 */
public final class WorldEditBinding {
    private WorldEditBinding() {}

    /** @param maxEditVolume the configured edit cap; read once per request, on the request's game thread */
    public static void register(ApiContext ctx, WorldEditAdapter adapter, ActorScope scope, LongSupplier maxEditVolume) {
        ctx.integration("worldedit", adapter::installed);
        SchematicFiles schematics = new SchematicFiles(adapter::schematicsDirectory);
        for (WorldEditSchema.Operation op : WorldEditSchema.OPERATIONS) {
            String name = op.name();
            if (WorldEditActions.kind(name) == WorldEditActions.Kind.READ) {
                ctx.resources.register(name, new ResourceDef(name, op.description() + paramsHint(scope.params()),
                        Availability.LOCAL, q -> adapter.installed()
                                ? run(adapter, scope, name, q, maxEditVolume, schematics)
                                : Unavailable.of(name, Reason.MOD_NOT_INSTALLED)));
            } else {
                ActionDef.Builder b = ActionDef.builder(name, op.description());
                op.params().forEach(b::param);
                scope.params().forEach(b::param);
                ctx.actions.register(name, b.handler(p -> {
                    if (!adapter.installed()) throw ApiException.unavailable("WorldEdit is not installed");
                    return run(adapter, scope, name, p, maxEditVolume, schematics);
                }));
            }
        }
    }

    private static Object run(WorldEditAdapter adapter, ActorScope scope, String name, Params p,
            LongSupplier maxEditVolume, SchematicFiles schematics) {
        long cap = maxEditVolume.getAsLong();
        return scope.call(p, actor -> WorldEditActions.dispatch(adapter, name, actor, p, cap, schematics));
    }

    private static String paramsHint(List<ParamDef> params) {
        if (params.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (ParamDef d : params) parts.add(d.name() + (d.required() ? " (required)" : "") + ": " + d.description());
        return ". Params: " + String.join("; ", parts);
    }
}

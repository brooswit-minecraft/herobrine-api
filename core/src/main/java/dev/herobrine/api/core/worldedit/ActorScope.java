package dev.herobrine.api.core.worldedit;

import dev.herobrine.api.core.ParamDef;
import dev.herobrine.api.core.Params;
import java.util.List;
import java.util.function.Function;

/**
 * How a platform answers "who is this request acting as in WorldEdit, and on which thread": it resolves
 * the WorldEdit actor from the request (the client's own player; on a dedicated server, a named online
 * player) and runs the work on the thread that owns that actor's world. The actor is resolved per request
 * and never cached, so every call gets that actor's own WorldEdit session.
 */
public interface ActorScope {
    /** Parameters this scope adds to every {@code worldedit.*} action and resource (e.g. {@code player}). */
    List<ParamDef> params();

    /** Resolves the actor {@code p} names and runs {@code work} with it on its world's server thread. */
    <T> T call(Params p, Function<Object, T> work);
}

package dev.herobrine.api.core;

import java.util.function.Function;

/** A readable resource, served at GET /api/v1/resources/{id}. The supplier runs on the game thread. */
public record ResourceDef(
        String id,
        String description,
        Availability availability,
        Function<Params, Object> supplier) {}

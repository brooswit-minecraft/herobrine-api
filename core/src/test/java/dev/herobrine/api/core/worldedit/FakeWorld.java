package dev.herobrine.api.core.worldedit;

import com.sk89q.worldedit.world.World;

/** A WorldEdit world; equal by name, as the real worlds compare. */
public record FakeWorld(String name) implements World {}

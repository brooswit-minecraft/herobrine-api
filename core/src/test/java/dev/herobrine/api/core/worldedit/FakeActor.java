package dev.herobrine.api.core.worldedit;

import com.sk89q.worldedit.extension.platform.Actor;

/** A distinct identity per actor, matching WorldEdit's per-player {@code LocalSession}s. */
public final class FakeActor implements Actor {
    public final String name;
    public FakeActor(String name) { this.name = name; }
    @Override public String toString() { return "Actor(" + name + ")"; }
}

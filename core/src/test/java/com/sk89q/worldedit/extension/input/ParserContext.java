package com.sk89q.worldedit.extension.input;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.world.World;

/**
 * Test double with the real ParserContext's state and contract (verified in 7.3.8): restricted by default,
 * {@code setWorld} also sets the extent to that world, and each {@code requireX} throws
 * {@code InputParseException} when X was never set - which is how the real parsers fail when a context lacks
 * what they read.
 */
public class ParserContext {
    private Extent extent;
    private LocalSession session;
    private World world;
    private Actor actor;
    private boolean restricted = true;

    public Extent getExtent() { return extent; }
    public void setExtent(Extent extent) { this.extent = extent; }
    public LocalSession getSession() { return session; }
    public void setSession(LocalSession session) { this.session = session; }
    public World getWorld() { return world; }
    public void setWorld(World world) {
        this.world = world;
        setExtent(world);
    }
    public Actor getActor() { return actor; }
    public void setActor(Actor actor) { this.actor = actor; }
    public boolean isRestricted() { return restricted; }
    public void setRestricted(boolean restricted) { this.restricted = restricted; }

    public Extent requireExtent() throws InputParseException {
        if (extent == null) throw new InputParseException("missing extent");
        return extent;
    }
    public LocalSession requireSession() throws InputParseException {
        if (session == null) throw new InputParseException("missing session");
        return session;
    }
    public World requireWorld() throws InputParseException {
        if (world == null) throw new InputParseException("missing world");
        return world;
    }
    public Actor requireActor() throws InputParseException {
        if (actor == null) throw new InputParseException("missing actor");
        return actor;
    }
}

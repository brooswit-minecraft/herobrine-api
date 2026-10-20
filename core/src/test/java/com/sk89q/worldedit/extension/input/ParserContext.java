package com.sk89q.worldedit.extension.input;
import com.sk89q.worldedit.extension.platform.Actor;

/** Test double: only {@code setActor} is exercised by the adapter. */
public class ParserContext {
    public Actor actor;
    public void setActor(Actor actor) { this.actor = actor; }
}

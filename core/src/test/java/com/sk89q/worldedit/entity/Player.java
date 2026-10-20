package com.sk89q.worldedit.entity;

import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.Locatable;

/** Test double: real Player is {@code extends Entity, Actor}, and Entity is Locatable. */
public interface Player extends Actor, Locatable {
}

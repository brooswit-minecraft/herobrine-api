package com.sk89q.worldedit.session;

/** Test double: like the real SessionOwner, an owner is identified by its session key. */
public interface SessionOwner {
    SessionKey getSessionKey();
}

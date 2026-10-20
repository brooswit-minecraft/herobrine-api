package com.sk89q.worldedit.session;

import com.sk89q.worldedit.LocalSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Test double. Like the real {@code SessionManager.get(SessionOwner)}, sessions are keyed by the owner's
 * session key UUID, not by object identity - a fresh actor object for the same player (which is what
 * {@code NeoForgeAdapter.adaptPlayer} hands out on every call) gets that player's existing session.
 */
public final class SessionManager {
    private final Map<UUID, LocalSession> sessions = new HashMap<>();

    public synchronized LocalSession get(SessionOwner owner) {
        return sessions.computeIfAbsent(owner.getSessionKey().getUniqueId(), k -> new LocalSession());
    }
}

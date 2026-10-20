package com.sk89q.worldedit.session;

import com.sk89q.worldedit.LocalSession;
import java.util.IdentityHashMap;
import java.util.Map;

public final class SessionManager {
    private final Map<SessionOwner, LocalSession> sessions = new IdentityHashMap<>();

    public synchronized LocalSession get(SessionOwner owner) {
        return sessions.computeIfAbsent(owner, o -> new LocalSession());
    }
}

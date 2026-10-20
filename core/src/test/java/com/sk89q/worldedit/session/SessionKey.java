package com.sk89q.worldedit.session;

import java.util.UUID;

/** Test double: the real SessionKey's identity, which SessionManager keys sessions by. */
public interface SessionKey {
    UUID getUniqueId();
}

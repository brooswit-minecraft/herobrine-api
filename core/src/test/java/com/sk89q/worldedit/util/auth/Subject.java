package com.sk89q.worldedit.util.auth;

/** Test double: the real Subject's permission check, which Actor extends. */
public interface Subject {
    boolean hasPermission(String permission);
}

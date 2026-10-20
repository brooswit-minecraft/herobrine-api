package com.sk89q.worldedit.extension.platform;

import com.sk89q.worldedit.session.SessionOwner;
import com.sk89q.worldedit.util.auth.Subject;

/** Test double: real Actor is {@code extends Identifiable, SessionOwner, Subject}. */
public interface Actor extends SessionOwner, Subject {
}

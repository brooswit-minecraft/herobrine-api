package com.sk89q.worldedit.extension.platform;
import com.sk89q.worldedit.session.SessionOwner;
/** Test double: real Actor is {@code extends Identifiable, SessionOwner, Subject}; only SessionOwner matters here. */
public interface Actor extends SessionOwner {
}

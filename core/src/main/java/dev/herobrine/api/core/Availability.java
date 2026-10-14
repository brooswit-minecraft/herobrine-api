package dev.herobrine.api.core;

/** Where a piece of state comes from; the API never fabricates state it cannot obtain. */
public enum Availability {
    /** Held locally by the client (options, input, GUI, camera). */
    LOCAL,
    /** Synchronized from the server during normal play. */
    SERVER_SYNCED,
    /** Obtainable through a normal client request (e.g. command suggestions). */
    CLIENT_REQUEST,
    /** Needs a cooperating server-side component. */
    REQUIRES_SERVER_MOD,
    /** Not accessible to a client at all. */
    UNAVAILABLE
}

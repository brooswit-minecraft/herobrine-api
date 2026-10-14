package dev.herobrine.api.core;

/** How far an action has progressed (spec 10.4). */
public enum Stage {
    /** Queued/long-running; not finished. */
    ACCEPTED,
    /** Performed on the client; the server may not have seen it yet. */
    EXECUTED_LOCALLY,
    /** The server acknowledged it. */
    SERVER_ACKNOWLEDGED,
    /** Resulting game state was observed. */
    CONFIRMED
}

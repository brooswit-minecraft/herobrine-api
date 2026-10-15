package dev.herobrine.api.core;

/** Explicit marker returned in place of a value the client cannot legitimately provide. Never substitute a guess. */
public record Unavailable(String property, boolean available, Reason reason) {
    public static Unavailable of(String property, Reason reason) { return new Unavailable(property, false, reason); }
}

package dev.herobrine.api.core;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** Marshals work onto the Minecraft client thread. HTTP workers must never touch game state directly. */
public interface GameThread {
    <T> CompletableFuture<T> submit(Callable<T> task);
}

package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.GameThread;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;

/**
 * Runs tasks on the dedicated server's main thread via {@link MinecraftServer#execute}. Mirrors
 * {@code MinecraftGameThread}'s pattern exactly, but references no {@code net.minecraft.client.*}
 * class -- {@link MinecraftServer} is common code, present and safe to load on a dedicated server.
 */
final class DedicatedServerGameThread implements GameThread {
    private final MinecraftServer server;

    DedicatedServerGameThread(MinecraftServer server) { this.server = server; }

    @Override
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        server.execute(() -> {
            try { f.complete(task.call()); }
            catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f;
    }
}

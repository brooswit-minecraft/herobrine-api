package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.GameThread;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/** Runs tasks on the Minecraft client thread. */
final class MinecraftGameThread implements GameThread {
    @Override
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try { f.complete(task.call()); }
            catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f;
    }
}

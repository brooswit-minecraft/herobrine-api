package dev.herobrine.api.neoforge;

import com.mojang.logging.LogUtils;
import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.ApiServer;
import dev.herobrine.api.core.Limits;
import dev.herobrine.api.core.Tokens;
import java.io.IOException;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Starts and stops the HTTP server at runtime (config + /herobrine_api command). Client thread only. */
final class ApiController {
    private static final Logger LOG = LogUtils.getLogger();

    private final ApiContext ctx;
    private Tokens tokens;
    private ApiServer server;
    private int port;

    ApiController(ApiContext ctx) { this.ctx = ctx; }

    boolean isRunning() { return server != null; }
    int port() { return port; }

    /**
     * @return the bound port. Tokens live in config/herobrine_api.token (full control) and
     * config/herobrine_api.read-token (read only); they are created once and survive restarts.
     */
    synchronized int start() throws IOException {
        if (server != null) return port;
        Path dir = FMLPaths.CONFIGDIR.get();
        if (tokens == null) tokens = Tokens.loadOrCreate(dir.resolve("herobrine_api.token"), dir.resolve("herobrine_api.read-token"));
        ApiServer s = new ApiServer(ctx, tokens, Limits.defaults());
        int bound = s.start(ClientConfig.PORT.get());
        server = s;
        port = bound;
        LOG.info("Herobrine API on http://127.0.0.1:{}/api/v1 (tokens in {})", bound, dir);
        return bound;
    }

    /** Stops the server and releases any keys it was holding. The token files are kept. */
    synchronized void stop() {
        if (server == null) return;
        server.stop();
        server = null;
        ClientBindings.releaseAllKeys();
        LOG.info("Herobrine API stopped");
    }

    /** Replaces both tokens; the old ones stop working immediately (no restart needed). */
    synchronized void regenerateTokens() throws IOException {
        if (tokens == null) {
            Path dir = FMLPaths.CONFIGDIR.get();
            tokens = Tokens.loadOrCreate(dir.resolve("herobrine_api.token"), dir.resolve("herobrine_api.read-token"));
        }
        tokens.regenerate();
        LOG.info("Herobrine API tokens regenerated");
    }
}

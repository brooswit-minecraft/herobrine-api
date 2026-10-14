package dev.herobrine.api.neoforge;

import com.mojang.logging.LogUtils;
import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.ApiServer;
import dev.herobrine.api.core.Limits;
import dev.herobrine.api.core.TokenStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Starts and stops the HTTP server at runtime (config + /herobrine_api command). Client thread only. */
final class ApiController {
    private static final Logger LOG = LogUtils.getLogger();

    private final ApiContext ctx;
    private ApiServer server;
    private int port;

    ApiController(ApiContext ctx) { this.ctx = ctx; }

    boolean isRunning() { return server != null; }
    int port() { return port; }

    /** @return the bound port. A fresh admin token is written to config/herobrine_api.token. */
    synchronized int start() throws IOException {
        if (server != null) return port;
        TokenStore tokens = new TokenStore();
        String secret = tokens.create("local-admin", Set.of("*")).secret();
        ApiServer s = new ApiServer(ctx, tokens, Limits.defaults());
        int bound = s.start(ClientConfig.PORT.get());
        server = s;
        port = bound;
        writeTokenFile(secret);
        LOG.info("Herobrine API on http://127.0.0.1:{}/api/v1 (admin token in {})", bound, tokenFile());
        return bound;
    }

    /** Stops the server, cancels all running operations and removes the token file. */
    synchronized void stop() {
        if (server == null) return;
        server.stop();
        server = null;
        int cancelled = ctx.operations.cancelAll();
        try { Files.deleteIfExists(tokenFile()); } catch (IOException e) { LOG.warn("Could not delete token file", e); }
        LOG.info("Herobrine API stopped ({} operations cancelled)", cancelled);
    }

    private static Path tokenFile() { return FMLPaths.CONFIGDIR.get().resolve("herobrine_api.token"); }

    private static void writeTokenFile(String secret) throws IOException {
        Path f = tokenFile();
        Files.deleteIfExists(f);
        Files.writeString(f, secret);
        try { // owner-only where the filesystem supports it
            Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
        }
    }
}

package dev.herobrine.api.neoforge;

import com.mojang.logging.LogUtils;
import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.ApiServer;
import dev.herobrine.api.core.Limits;
import dev.herobrine.api.core.Tokens;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Starts and stops the HTTP server at runtime. Common to both the client and dedicated-server
 * lifecycles -- it knows nothing about {@code ClientBindings} or any other client-only concept.
 * Platform-specific teardown (e.g. releasing held client keys) is injected via {@code afterStop}.
 */
final class ApiController {
    private static final Logger LOG = LogUtils.getLogger();

    private final ApiContext ctx;
    private final Supplier<Tokens> tokensLoader;
    private final IntSupplier portSetting;
    private final Runnable afterStop;
    private Tokens tokens;
    private ApiServer server;
    private int port;

    /**
     * @param tokensLoader  loads-or-creates the backing secret(s); may throw unchecked wrapping IOException
     * @param portSetting   the configured loopback port at the moment {@link #start()} runs
     * @param afterStop     platform-specific cleanup run after the HTTP server stops (e.g. releasing
     *                      held client keys); pass {@code () -> {}} when there is nothing to do
     */
    ApiController(ApiContext ctx, Supplier<Tokens> tokensLoader, IntSupplier portSetting, Runnable afterStop) {
        this.ctx = ctx;
        this.tokensLoader = tokensLoader;
        this.portSetting = portSetting;
        this.afterStop = afterStop;
    }

    boolean isRunning() { return server != null; }
    int port() { return port; }

    /** @return the bound port. */
    synchronized int start() throws IOException {
        if (server != null) return port;
        if (tokens == null) tokens = tokensLoader.get();
        ApiServer s = new ApiServer(ctx, tokens, Limits.defaults());
        int bound = s.start(portSetting.getAsInt());
        server = s;
        port = bound;
        LOG.info("Herobrine API ({}) on http://127.0.0.1:{}/api/v1", ctx.platform, bound);
        return bound;
    }

    /** Stops the server, then runs platform-specific cleanup. Token files are kept. */
    synchronized void stop() {
        if (server == null) return;
        server.stop();
        server = null;
        afterStop.run();
        LOG.info("Herobrine API ({}) stopped", ctx.platform);
    }

    /** Replaces the secret(s); the old one(s) stop working immediately (no restart needed). */
    synchronized void regenerateTokens() throws IOException {
        if (tokens == null) tokens = tokensLoader.get();
        tokens.regenerate();
        LOG.info("Herobrine API ({}) token(s) regenerated", ctx.platform);
    }

    /** Builds a {@link Tokens} loader for the client's two-file FULL/READ split. */
    static Supplier<Tokens> clientTokens(Path dir) {
        return () -> {
            try {
                return Tokens.loadOrCreate(dir.resolve(CLIENT_FULL_TOKEN_FILE), dir.resolve(CLIENT_READ_TOKEN_FILE));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    /**
     * Builds a {@link Tokens} loader for the dedicated server's single operator key, stored as the
     * {@code apiKey} field inside {@code serverconfig/herobrine_api-server.toml} (via {@code accessor})
     * rather than a separate file -- see {@link ServerApiKeyAccessor}.
     */
    static Supplier<Tokens> serverConfigTokens(Tokens.SecretAccessor accessor) {
        return () -> {
            try {
                return Tokens.loadOrCreateSingle(accessor);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    static final String CLIENT_FULL_TOKEN_FILE = "herobrine_api.token";
    static final String CLIENT_READ_TOKEN_FILE = "herobrine_api.read-token";
}

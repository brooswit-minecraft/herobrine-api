package dev.herobrine.api.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;

class ApiServerTest {
    static ApiServer server;
    static ApiContext ctx;
    static TokenStore tokens;
    static String base, admin, reader, controller, controller2;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        ctx = new ApiContext(new GameThread() {
            public <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> t) {
                try { return CompletableFuture.completedFuture(t.call()); }
                catch (Throwable e) { return CompletableFuture.failedFuture(e); }
            }
        });
        ctx.observedTick = () -> 42;
        ctx.capability("world.loadedChunks", () -> true);
        ctx.capability("world.unloadedChunks", () -> false);
        ctx.integration("baritone", () -> false);
        ctx.resources.register("test.info", new ResourceDef("test.info", "Test", Availability.LOCAL, q -> java.util.Map.of("ok", true)));
        ctx.resources.register("test.big", new ResourceDef("test.big", "Big", Availability.LOCAL, q -> "x".repeat(10_000)));
        ctx.resources.register("test.missing", new ResourceDef("test.missing", "Missing", Availability.REQUIRES_SERVER_MOD,
                q -> Unavailable.of("entity.brain", Reason.SERVER_ONLY)));
        ctx.actions.register("test.echo", ActionDef.builder("test.echo", "Echo").permission("control.chat")
                .param(ParamDef.required("text", "string", "Text")).handler(p -> java.util.Map.of("text", p.string("text"))));
        ctx.actions.register("test.slow", ActionDef.builder("test.slow", "Slow").lock("player.movement").handler(p -> new LongRunning() {
            int n = 0;
            public Object tick() { return ++n >= 3 ? "done" : null; }
            public Object progress() { return java.util.Map.of("n", n); }
        }));
        ctx.actions.register("test.forever", ActionDef.builder("test.forever", "Never finishes").handler(p -> new LongRunning() {
            public Object tick() { return null; }
        }));
        ctx.actions.register("test.look", ActionDef.builder("test.look", "Instant, locked").lock("player.movement").handler(p -> "looked"));
        ctx.actions.register("test.fail", ActionDef.builder("test.fail", "Fails").handler(p -> { throw ApiException.unavailable("Player is not connected to a world"); }));
        ctx.actions.register("test.staged", ActionDef.builder("test.staged", "Staged").handler(p -> new Staged("x", Stage.CONFIRMED)));

        tokens = new TokenStore();
        admin = tokens.create("admin", Set.of("*")).secret();
        reader = tokens.create("reader", Set.of("read.*")).secret();
        controller = tokens.create("c1", Set.of("read.*", "control.*")).secret();
        controller2 = tokens.create("c2", Set.of("read.*", "control.*")).secret();
        server = new ApiServer(ctx, tokens, new Limits(32, 10_000, 5_000, 1 << 20, 8, 10_000, 1000, 5));
        base = "http://127.0.0.1:" + server.start(0) + "/api/v1";
    }

    @AfterAll
    static void stop() { server.stop(); }

    static HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonObject obj(HttpResponse<String> r) { return JsonParser.parseString(r.body()).getAsJsonObject(); }

    static HttpResponse<String> exec(String token, String action, String params) throws Exception {
        return call("POST", "/actions/execute", "{\"action\":\"" + action + "\",\"parameters\":" + params + "}", token);
    }

    static String startOp(String token, String action) throws Exception {
        return obj(exec(token, action, "{}")).getAsJsonObject("result").get("operationId").getAsString();
    }

    static String status(String token, String id) throws Exception {
        return obj(call("GET", "/operations/" + id, null, token)).get("status").getAsString();
    }

    @Test void requiresValidToken() throws Exception {
        assertEquals(401, call("GET", "", null, null).statusCode());
        assertEquals(401, call("GET", "", null, "wrong").statusCode());
        assertEquals(200, call("GET", "", null, admin).statusCode());
    }

    @Test void discoveryAndCapabilities() throws Exception {
        for (String p : new String[]{"/resources", "/actions", "/events", "/mods", "/schema", "/openapi.json", "/operations"})
            assertEquals(200, call("GET", p, null, admin).statusCode(), p);
        JsonObject caps = obj(call("GET", "/capabilities", null, reader));
        assertTrue(caps.get("client").getAsBoolean());
        assertFalse(caps.get("serverCompanion").getAsBoolean());
        assertFalse(caps.getAsJsonObject("capabilities").get("world.unloadedChunks").getAsBoolean());
        assertFalse(caps.getAsJsonObject("integrations").get("baritone").getAsBoolean());
        JsonObject doc = obj(call("GET", "/openapi.json", null, admin));
        assertTrue(doc.getAsJsonObject("paths").has("/api/v1/resources/test.info"));
    }

    @Test void resourceEnvelopeAndUnavailable() throws Exception {
        JsonObject r = obj(call("GET", "/resources/test.info", null, reader));
        assertTrue(r.get("success").getAsBoolean());
        assertEquals(42, r.get("observedTick").getAsLong());
        assertEquals("client", r.get("source").getAsString());
        JsonObject m = obj(call("GET", "/resources/test.missing", null, reader)).getAsJsonObject("value");
        assertFalse(m.get("available").getAsBoolean());
        assertEquals("SERVER_ONLY", m.get("reason").getAsString());
        assertEquals(404, call("GET", "/resources/nope", null, reader).statusCode());
    }

    @Test void permissionsEnforced() throws Exception {
        assertEquals(403, exec(reader, "test.echo", "{\"text\":\"hi\"}").statusCode());
        assertEquals(200, exec(controller, "test.echo", "{\"text\":\"hi\"}").statusCode());
        assertEquals(403, call("GET", "/admin/tokens", null, controller).statusCode());
        assertEquals(200, call("GET", "/admin/tokens", null, admin).statusCode());
    }

    @Test void tokenCreateAndImmediateRevoke() throws Exception {
        JsonObject created = obj(call("POST", "/admin/tokens", "{\"name\":\"tmp\",\"permissions\":[\"read.client\"]}", admin));
        String secret = created.get("secret").getAsString();
        String id = created.getAsJsonObject("token").get("id").getAsString();
        assertEquals(200, call("GET", "/resources/test.info", null, secret).statusCode());
        assertEquals(400, call("POST", "/admin/tokens", "{\"name\":\"x\",\"permissions\":[\"bogus\"]}", admin).statusCode());
        assertEquals(200, call("DELETE", "/admin/tokens/" + id, null, admin).statusCode());
        assertEquals(401, call("GET", "/resources/test.info", null, secret).statusCode());
        assertTrue(call("GET", "/admin/audit", null, admin).body().contains("revoked token"));
    }

    @Test void standardResultsAndStages() throws Exception {
        JsonObject ok = obj(exec(controller, "test.echo", "{\"text\":\"hi\"}"));
        assertTrue(ok.get("success").getAsBoolean());
        assertEquals("test.echo", ok.get("action").getAsString());
        assertEquals("EXECUTED_LOCALLY", ok.get("stage").getAsString());
        assertEquals("CONFIRMED", obj(exec(controller, "test.staged", "{}")).get("stage").getAsString());
        var bad = exec(controller, "test.fail", "{}");
        assertEquals(409, bad.statusCode());
        JsonObject err = obj(bad).getAsJsonObject("error");
        assertEquals("ACTION_UNAVAILABLE", err.get("code").getAsString());
        assertFalse(obj(bad).get("success").getAsBoolean());
    }

    @Test void validation() throws Exception {
        assertEquals(400, exec(controller, "test.echo", "{}").statusCode());
        assertEquals(400, exec(controller, "test.echo", "{\"text\":1}").statusCode());
        assertEquals(400, exec(controller, "test.echo", "{\"text\":\"a\",\"x\":1}").statusCode());
        assertEquals(404, call("POST", "/actions/execute", "{\"action\":\"nope\"}", controller).statusCode());
        assertEquals(400, call("POST", "/actions/execute", "not json", controller).statusCode());
    }

    @Test void operationLifecycleOwnershipAndCancel() throws Exception {
        String id = startOp(controller, "test.slow");
        assertEquals("running", status(controller, id));
        assertEquals(403, call("GET", "/operations/" + id, null, controller2).statusCode());
        assertEquals(200, call("GET", "/operations/" + id, null, admin).statusCode());
        ctx.operations.tick();
        assertEquals(1, ((Number) ((java.util.Map<?, ?>) ctx.operations.get(id).progress).get("n")).intValue());
        ctx.operations.tick(); ctx.operations.tick();
        JsonObject done = obj(call("GET", "/operations/" + id, null, controller));
        assertEquals("completed", done.get("status").getAsString());
        assertEquals("done", done.get("result").getAsString());

        String id2 = startOp(controller, "test.forever");
        assertEquals(403, call("POST", "/operations/" + id2 + "/cancel", null, controller2).statusCode());
        assertEquals("cancelled", obj(call("POST", "/operations/" + id2 + "/cancel", null, controller)).get("status").getAsString());
        assertTrue(call("GET", "/operations?status=cancelled", null, controller).body().contains(id2));
        assertFalse(call("GET", "/operations", null, controller2).body().contains(id2));
    }

    @Test void resourceLocksArbitrateControllers() throws Exception {
        String id = startOp(controller, "test.slow");
        var conflict = exec(controller2, "test.look", "{}");
        assertEquals(409, conflict.statusCode());
        assertEquals("RESOURCE_LOCKED", obj(conflict).getAsJsonObject("error").get("code").getAsString());
        assertEquals(409, exec(controller2, "test.slow", "{}").statusCode());
        assertEquals(200, exec(controller, "test.look", "{}").statusCode()); // holder may act
        call("POST", "/operations/" + id + "/cancel", null, controller);
        assertEquals(200, exec(controller2, "test.look", "{}").statusCode()); // released
    }

    @Test void timeout() throws Exception {
        var r = call("POST", "/actions/execute", "{\"action\":\"test.forever\",\"timeoutMs\":1}", controller);
        String id = obj(r).getAsJsonObject("result").get("operationId").getAsString();
        Thread.sleep(20);
        ctx.operations.tick();
        assertEquals("timed_out", status(controller, id));
    }

    @Test void delayedAction() throws Exception {
        var r = call("POST", "/actions/execute", "{\"action\":\"test.echo\",\"parameters\":{\"text\":\"later\"},\"delayTicks\":2}", controller);
        assertEquals("ACCEPTED", obj(r).get("stage").getAsString());
        String id = obj(r).getAsJsonObject("result").get("operationId").getAsString();
        ctx.operations.tick(); ctx.operations.tick();
        assertEquals("running", status(controller, id));
        ctx.operations.tick();
        JsonObject done = obj(call("GET", "/operations/" + id, null, controller));
        assertEquals("completed", done.get("status").getAsString());
        assertEquals("later", done.getAsJsonObject("result").get("text").getAsString());
    }

    @Test void sequentialBatch() throws Exception {
        String body = "{\"mode\":\"sequential\",\"actions\":[{\"action\":\"test.echo\",\"parameters\":{\"text\":\"a\"}},{\"action\":\"test.slow\"},{\"action\":\"test.echo\",\"parameters\":{\"text\":\"b\"}}]}";
        String id = obj(call("POST", "/actions/batch", body, controller)).getAsJsonObject("result").get("operationId").getAsString();
        for (int i = 0; i < 12; i++) ctx.operations.tick();
        JsonObject done = obj(call("GET", "/operations/" + id, null, controller));
        assertEquals("completed", done.get("status").getAsString());
        assertEquals(3, done.getAsJsonObject("result").getAsJsonArray("results").size());
    }

    @Test void concurrentBatchAndLimits() throws Exception {
        String body = "{\"mode\":\"concurrent\",\"actions\":[{\"action\":\"test.echo\",\"parameters\":{\"text\":\"a\"}},{\"action\":\"test.fail\"}]}";
        var res = obj(call("POST", "/actions/batch", body, controller)).getAsJsonArray("results");
        assertTrue(res.get(0).getAsJsonObject().get("success").getAsBoolean());
        assertFalse(res.get(1).getAsJsonObject().get("success").getAsBoolean());
        String tooMany = "{\"actions\":[" + "{\"action\":\"test.look\"},".repeat(6) + "{\"action\":\"test.look\"}]}";
        assertEquals(400, call("POST", "/actions/batch", tooMany, controller).statusCode());
    }

    @Test void responseSizeLimit() throws Exception {
        var r = call("GET", "/resources/test.big", null, reader);
        assertEquals(413, r.statusCode());
        assertEquals("RESPONSE_TOO_LARGE", obj(r).getAsJsonObject("error").get("code").getAsString());
    }

    @Test void providersAreNamespacedAndPermissioned() throws Exception {
        assertTrue(ctx.install(new ResourceProvider() {
            public String modId() { return "examplemod"; }
            public void register(ApiRegistrar r) {
                r.resource("machines", "Machines", Availability.LOCAL, q -> java.util.List.of("a"));
                r.register(r.action("ping", "Ping").handler(p -> "pong"));
            }
        }));
        assertEquals(200, call("GET", "/resources/examplemod.machines", null, reader).statusCode());
        assertEquals(200, exec(controller, "examplemod.ping", "{}").statusCode());
        assertFalse(ctx.install(new ResourceProvider() {
            public String modId() { return "bad"; }
            public void register(ApiRegistrar r) { throw new IllegalStateException("boom"); }
        }));
        assertThrows(IllegalArgumentException.class, () -> new ApiRegistrar(ctx, "Bad-Id"));
    }

    @Test void pagination() {
        var all = java.util.stream.IntStream.range(0, 25).boxed().toList();
        var obj = new com.google.gson.JsonObject();
        obj.addProperty("limit", 10);
        obj.addProperty("offset", 20);
        Page<Integer> p = Page.of(all, new Params(obj), 5, 100);
        assertEquals(5, p.items().size());
        assertNull(p.nextOffset());
        assertEquals(10, Page.of(all, new Params(obj), 5, 100).limit());
    }

    @Test void controlBlockRefusesControlButNotReads() throws Exception {
        ctx.controlBlock = () -> "Control is disabled on multiplayer servers";
        try {
            var r = exec(controller, "test.echo", "{\"text\":\"hi\"}");
            assertEquals(409, r.statusCode());
            assertEquals("ACTION_UNAVAILABLE", obj(r).getAsJsonObject("error").get("code").getAsString());
            assertEquals(200, call("GET", "/resources/test.info", null, reader).statusCode());
            // batch (concurrent) reports per-item failure
            var b = obj(call("POST", "/actions/batch", "{\"mode\":\"concurrent\",\"actions\":[{\"action\":\"test.echo\",\"parameters\":{\"text\":\"a\"}}]}", controller));
            assertFalse(b.getAsJsonArray("results").get(0).getAsJsonObject().get("success").getAsBoolean());
        } finally {
            ctx.controlBlock = () -> null;
        }
        assertEquals(200, exec(controller, "test.echo", "{\"text\":\"hi\"}").statusCode());
    }

    @Test void delayedControlActionIsRecheckedWhenItRuns() throws Exception {
        var r = call("POST", "/actions/execute", "{\"action\":\"test.echo\",\"parameters\":{\"text\":\"x\"},\"delayTicks\":1}", controller);
        String id = obj(r).getAsJsonObject("result").get("operationId").getAsString();
        ctx.controlBlock = () -> "blocked now";
        try {
            ctx.operations.tick(); ctx.operations.tick();
        } finally {
            ctx.controlBlock = () -> null;
        }
        assertEquals("failed", status(controller, id));
    }

    @Test void cancelAllStopsRunningOperations() throws Exception {
        String id = startOp(controller, "test.forever");
        assertTrue(ctx.operations.cancelAll() >= 1);
        assertEquals("cancelled", status(controller, id));
    }
}

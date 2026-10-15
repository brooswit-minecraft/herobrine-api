package dev.herobrine.api.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ApiServerTest {
    static ApiServer server;
    static ApiContext ctx;
    static Tokens tokens;
    static String base, full, read;
    static final AtomicInteger counter = new AtomicInteger();
    static final List<Runnable> deferred = new ArrayList<>();
    static volatile boolean deferTasks;
    static final HttpClient http = HttpClient.newHttpClient();
    @TempDir static Path tmp;

    @BeforeAll
    static void start() throws Exception {
        ctx = new ApiContext(new GameThread() {
            public <T> CompletableFuture<T> submit(Callable<T> t) {
                CompletableFuture<T> f = new CompletableFuture<>();
                Runnable run = () -> {
                    try { f.complete(t.call()); } catch (Throwable e) { f.completeExceptionally(e); }
                };
                if (deferTasks) deferred.add(run); else run.run();
                return f;
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
        ctx.actions.register("test.echo", ActionDef.builder("test.echo", "Echo")
                .param(ParamDef.required("text", "string", "Text")).handler(p -> java.util.Map.of("text", p.string("text"))));
        ctx.actions.register("test.count", ActionDef.builder("test.count", "Counts calls").handler(p -> counter.incrementAndGet()));
        ctx.actions.register("test.fail", ActionDef.builder("test.fail", "Fails").handler(p -> { throw ApiException.unavailable("Player is not connected to a world"); }));
        ctx.actions.register("test.enum", ActionDef.builder("test.enum", "Enum").param(new ParamDef("shape", "string", true, "s", null, List.of("a", "b"))).handler(p -> p.string("shape")));

        tokens = Tokens.loadOrCreate(tmp.resolve("full.token"), tmp.resolve("read.token"));
        full = Files.readString(tokens.fullFile()).trim();
        read = Files.readString(tokens.readFile()).trim();
        server = new ApiServer(ctx, tokens, new Limits(32, 5_000, 1 << 20, 8, 300, 1000));
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

    // ---- auth ----

    @Test void requiresValidToken() throws Exception {
        assertEquals(401, call("GET", "", null, null).statusCode());
        assertEquals(401, call("GET", "", null, "wrong").statusCode());
        assertEquals(200, call("GET", "", null, full).statusCode());
        assertEquals(200, call("GET", "", null, read).statusCode());
    }

    @Test void readTokenReadsEverythingButCannotAct() throws Exception {
        for (String p : new String[]{"/resources", "/actions", "/events", "/capabilities", "/mods", "/schema", "/openapi.json", "/resources/test.info"})
            assertEquals(200, call("GET", p, null, read).statusCode(), p);
        var denied = exec(read, "test.echo", "{\"text\":\"hi\"}");
        assertEquals(403, denied.statusCode());
        assertEquals("PERMISSION_DENIED", obj(denied).getAsJsonObject("error").get("code").getAsString());
        assertFalse(obj(call("GET", "/actions", null, read)).getAsJsonObject("test.echo").get("permitted").getAsBoolean());
        assertTrue(obj(call("GET", "/actions", null, full)).getAsJsonObject("test.echo").get("permitted").getAsBoolean());
        assertEquals(200, exec(full, "test.echo", "{\"text\":\"hi\"}").statusCode());
    }

    // ---- discovery / resources ----

    @Test void capabilitiesAndOpenApi() throws Exception {
        JsonObject caps = obj(call("GET", "/capabilities", null, read));
        assertTrue(caps.get("client").getAsBoolean());
        assertFalse(caps.get("serverCompanion").getAsBoolean());
        assertFalse(caps.getAsJsonObject("capabilities").get("world.unloadedChunks").getAsBoolean());
        assertFalse(caps.getAsJsonObject("integrations").get("baritone").getAsBoolean());
        JsonObject doc = obj(call("GET", "/openapi.json", null, read));
        assertTrue(doc.getAsJsonObject("paths").has("/api/v1/resources/test.info"));
    }

    @Test void resourceEnvelopeAndUnavailableMarker() throws Exception {
        JsonObject r = obj(call("GET", "/resources/test.info", null, read));
        assertTrue(r.get("success").getAsBoolean());
        assertEquals(42, r.get("observedTick").getAsLong());
        assertEquals("client", r.get("source").getAsString());
        JsonObject m = obj(call("GET", "/resources/test.missing", null, read)).getAsJsonObject("value");
        assertFalse(m.get("available").getAsBoolean());
        assertEquals("SERVER_ONLY", m.get("reason").getAsString());
        assertEquals(404, call("GET", "/resources/nope", null, read).statusCode());
    }

    @Test void responseSizeLimit() throws Exception {
        var r = call("GET", "/resources/test.big", null, read);
        assertEquals(413, r.statusCode());
        assertEquals("RESPONSE_TOO_LARGE", obj(r).getAsJsonObject("error").get("code").getAsString());
    }

    // ---- actions ----

    @Test void actionReturnsWhatTheHandlerReturned() throws Exception {
        JsonObject ok = obj(exec(full, "test.echo", "{\"text\":\"hi\"}"));
        assertTrue(ok.get("success").getAsBoolean());
        assertEquals("test.echo", ok.get("action").getAsString());
        assertEquals("hi", ok.getAsJsonObject("result").get("text").getAsString());
        var bad = exec(full, "test.fail", "{}");
        assertEquals(409, bad.statusCode());
        assertEquals("ACTION_UNAVAILABLE", obj(bad).getAsJsonObject("error").get("code").getAsString());
    }

    @Test void validation() throws Exception {
        assertEquals(400, exec(full, "test.echo", "{}").statusCode());
        assertEquals(400, exec(full, "test.echo", "{\"text\":1}").statusCode());
        assertEquals(400, exec(full, "test.echo", "{\"text\":\"a\",\"x\":1}").statusCode());
        assertEquals(400, exec(full, "test.enum", "{\"shape\":\"c\"}").statusCode());
        assertEquals(200, exec(full, "test.enum", "{\"shape\":\"a\"}").statusCode());
        assertEquals(404, call("POST", "/actions/execute", "{\"action\":\"nope\"}", full).statusCode());
        assertEquals(400, call("POST", "/actions/execute", "not json", full).statusCode());
        assertEquals(400, call("POST", "/actions/execute", "{\"action\":{}}", full).statusCode());
        assertEquals(400, call("POST", "/actions/execute", "{\"action\":\"test.count\",\"parameters\":\"x\"}", full).statusCode());
    }

    @Test void controlBlockRefusesActionsButNotReads() throws Exception {
        ctx.controlBlock = () -> "Control is disabled on multiplayer servers";
        try {
            var r = exec(full, "test.echo", "{\"text\":\"hi\"}");
            assertEquals(409, r.statusCode());
            assertEquals("ACTION_UNAVAILABLE", obj(r).getAsJsonObject("error").get("code").getAsString());
            assertEquals(200, call("GET", "/resources/test.info", null, full).statusCode());
        } finally {
            ctx.controlBlock = () -> null;
        }
        assertEquals(200, exec(full, "test.echo", "{\"text\":\"hi\"}").statusCode());
    }

    @Test void aTimedOutRequestNeverRunsLater() throws Exception {
        counter.set(0);
        deferTasks = true;
        try {
            var r = exec(full, "test.count", "{}");
            assertEquals(503, r.statusCode());
            assertEquals("GAME_THREAD_BUSY", obj(r).getAsJsonObject("error").get("code").getAsString());
        } finally {
            deferTasks = false;
        }
        assertEquals(1, deferred.size());
        deferred.forEach(Runnable::run); // the game thread finally catches up
        deferred.clear();
        assertEquals(0, counter.get(), "the abandoned action must not have run");
        assertEquals(200, exec(full, "test.count", "{}").statusCode());
        assertEquals(1, counter.get());
    }

    // ---- tokens ----

    @Test void tokensPersistAndRotate() throws Exception {
        Path a = tmp.resolve("p-full"), b = tmp.resolve("p-read");
        Tokens t1 = Tokens.loadOrCreate(a, b);
        String f1 = Files.readString(a).trim(), r1 = Files.readString(b).trim();
        assertNotEquals(f1, r1);
        assertEquals(Tokens.Access.FULL, t1.authenticate(f1).orElseThrow());
        assertEquals(Tokens.Access.READ, t1.authenticate(r1).orElseThrow());
        assertTrue(t1.authenticate("nope").isEmpty());
        assertTrue(t1.authenticate(null).isEmpty());

        Tokens t2 = Tokens.loadOrCreate(a, b); // "restart": same secrets
        assertEquals(Tokens.Access.FULL, t2.authenticate(f1).orElseThrow());

        t2.regenerate();
        assertTrue(t2.authenticate(f1).isEmpty());
        assertTrue(t2.authenticate(r1).isEmpty());
        assertNotEquals(f1, Files.readString(a).trim());
        assertEquals(Tokens.Access.FULL, t2.authenticate(Files.readString(a).trim()).orElseThrow());

        Files.writeString(b, Files.readString(a)); // same secret in both files is repaired
        Tokens t3 = Tokens.loadOrCreate(a, b);
        assertNotEquals(Files.readString(a).trim(), Files.readString(b).trim());
        assertEquals(Tokens.Access.FULL, t3.authenticate(Files.readString(a).trim()).orElseThrow());
        try {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(a)));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    // ---- providers / helpers ----

    @Test void providersAreNamespaced() throws Exception {
        assertTrue(ctx.install(new ResourceProvider() {
            public String modId() { return "examplemod"; }
            public void register(ApiRegistrar r) {
                r.resource("machines", "Machines", Availability.LOCAL, q -> java.util.List.of("a"));
                r.register(r.action("ping", "Ping").handler(p -> "pong"));
            }
        }));
        assertEquals(200, call("GET", "/resources/examplemod.machines", null, read).statusCode());
        assertEquals(200, exec(full, "examplemod.ping", "{}").statusCode());
        assertFalse(ctx.install(new ResourceProvider() {
            public String modId() { return "bad"; }
            public void register(ApiRegistrar r) { throw new IllegalStateException("boom"); }
        }));
        assertThrows(IllegalArgumentException.class, () -> new ApiRegistrar(ctx, "Bad-Id"));
    }

    @Test void pagination() {
        var all = java.util.stream.IntStream.range(0, 25).boxed().toList();
        var o = new JsonObject();
        o.addProperty("limit", 10);
        o.addProperty("offset", 20);
        Page<Integer> p = Page.of(all, new Params(o), 5, 100);
        assertEquals(5, p.items().size());
        assertNull(p.nextOffset());
        assertEquals(10, p.limit());
    }

    @Test void chatTextValidation() {
        assertEquals("hello", Validation.chatText("hello"));
        for (String bad : new String[]{"", "  ", "/op me", "a\nb", "x§cred", "z".repeat(257)})
            assertThrows(ApiException.class, () -> Validation.chatText(bad), bad);
        assertEquals(256, Validation.chatText("z".repeat(256)).length());
    }

    @Test void settingValueValidation() {
        assertEquals("a b-1.5", Validation.settingValue("a b-1.5"));
        for (String bad : new String[]{"", "a\nb", "a\u0000b", "z".repeat(201)})
            assertThrows(ApiException.class, () -> Validation.settingValue(bad), bad);
    }

    @Test void buildValidation() {
        assertEquals("minecraft:stone", Validation.resourceId("minecraft:stone"));
        for (String bad : new String[]{"Stone", "a b", "x;y", "", "minecraft:"})
            assertThrows(ApiException.class, () -> Validation.resourceId(bad), bad);
        assertEquals("house.schem", Validation.schematicName("house.schem"));
        for (String bad : new String[]{"../x.schem", "a/b.schem", "a\\b.schem", "x.txt", "..schem", "", "/etc/passwd.nbt", "a b.schem"})
            assertThrows(ApiException.class, () -> Validation.schematicName(bad), bad);
        assertEquals(27, Validation.boxVolume(0, 0, 0, 2, 2, 2, 100));
        assertEquals(27, Validation.boxVolume(2, 2, 2, 0, 0, 0, 100));
        assertThrows(ApiException.class, () -> Validation.boxVolume(0, 0, 0, 99, 99, 99, 1000));
        assertThrows(ApiException.class, () -> Validation.boxVolume(0, 0, 0, Integer.MAX_VALUE, 0, 0, Long.MAX_VALUE));
    }
}

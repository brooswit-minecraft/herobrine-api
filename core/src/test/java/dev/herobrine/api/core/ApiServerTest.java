package dev.herobrine.api.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;

class ApiServerTest {
    static ApiServer server;
    static ApiContext ctx;
    static String base;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        ctx = new ApiContext(new GameThread() {
            public <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> t) {
                try { return CompletableFuture.completedFuture(t.call()); }
                catch (Throwable e) { return CompletableFuture.failedFuture(e); }
            }
        });
        ctx.resources.register("test.info", new ResourceDef("test.info", "Test", Availability.LOCAL, q -> java.util.Map.of("ok", true)));
        ctx.actions.register("test.echo", ActionDef.builder("test.echo", "Echo")
                .param(ParamDef.required("text", "string", "Text")).handler(p -> java.util.Map.of("text", p.string("text"))));
        ctx.actions.register("test.slow", ActionDef.builder("test.slow", "Slow").handler(p -> new LongRunning() {
            int n = 0;
            public Object tick() { return ++n >= 3 ? "done" : null; }
        }));
        server = new ApiServer(ctx, "secret");
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

    @Test void requiresToken() throws Exception {
        assertEquals(401, call("GET", "", null, null).statusCode());
        assertEquals(401, call("GET", "", null, "wrong").statusCode());
        assertEquals(200, call("GET", "", null, "secret").statusCode());
    }

    @Test void discovery() throws Exception {
        for (String p : new String[]{"/resources", "/actions", "/events", "/capabilities", "/mods", "/schema", "/openapi.json"})
            assertEquals(200, call("GET", p, null, "secret").statusCode(), p);
        JsonObject doc = JsonParser.parseString(call("GET", "/openapi.json", null, "secret").body()).getAsJsonObject();
        assertTrue(doc.getAsJsonObject("paths").has("/api/v1/resources/test.info"));
    }

    @Test void resourceRead() throws Exception {
        var r = call("GET", "/resources/test.info", null, "secret");
        assertTrue(JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("value").get("ok").getAsBoolean());
        assertEquals(404, call("GET", "/resources/nope", null, "secret").statusCode());
    }

    @Test void actionValidation() throws Exception {
        assertEquals(200, call("POST", "/actions/execute", "{\"action\":\"test.echo\",\"parameters\":{\"text\":\"hi\"}}", "secret").statusCode());
        assertEquals(400, call("POST", "/actions/execute", "{\"action\":\"test.echo\",\"parameters\":{}}", "secret").statusCode());
        assertEquals(400, call("POST", "/actions/execute", "{\"action\":\"test.echo\",\"parameters\":{\"text\":1}}", "secret").statusCode());
        assertEquals(400, call("POST", "/actions/execute", "{\"action\":\"test.echo\",\"parameters\":{\"text\":\"a\",\"x\":1}}", "secret").statusCode());
        assertEquals(404, call("POST", "/actions/execute", "{\"action\":\"nope\"}", "secret").statusCode());
        assertEquals(400, call("POST", "/actions/execute", "not json", "secret").statusCode());
    }

    @Test void longRunningOperation() throws Exception {
        var r = call("POST", "/actions/execute", "{\"action\":\"test.slow\"}", "secret");
        String id = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("result").get("operation").getAsString();
        String state = JsonParser.parseString(call("GET", "/operations/" + id, null, "secret").body()).getAsJsonObject().get("state").getAsString();
        assertEquals("RUNNING", state);
        for (int i = 0; i < 3; i++) ctx.operations.tick();
        var done = JsonParser.parseString(call("GET", "/operations/" + id, null, "secret").body()).getAsJsonObject();
        assertEquals("COMPLETED", done.get("state").getAsString());
        assertEquals("done", done.get("result").getAsString());
    }
}

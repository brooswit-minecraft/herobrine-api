package dev.herobrine.api.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Embedded HTTP server. Binds to loopback only and requires a bearer token.
 * All game-state access is marshalled through {@link GameThread}.
 */
public final class ApiServer {
    private static final String P = "/api/v1";
    private static final long GAME_THREAD_TIMEOUT_MS = 10_000;

    private final ApiContext ctx;
    private final String token;
    private HttpServer http;

    public ApiServer(ApiContext ctx, String token) {
        this.ctx = ctx;
        this.token = token;
    }

    public int start(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "herobrine-api-http");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/", this::handle);
        http.start();
        return http.getAddress().getPort();
    }

    public void stop() { if (http != null) http.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex)) throw new ApiException(401, "unauthorized", "Missing or invalid bearer token");
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            route(ex, method, path);
        } catch (ApiException e) {
            error(ex, e.status, e.code, e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "internal_error", String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        if (token == null) return true;
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(h.substring(7).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private void route(HttpExchange ex, String method, String path) throws Exception {
        if (!path.equals(P) && !path.startsWith(P + "/")) throw ApiException.notFound("Unknown path " + path);
        String rest = path.substring(P.length());
        switch (rest.isEmpty() ? "/" : rest) {
            case "/" -> { need(method, "GET"); json(ex, 200, index()); }
            case "/resources" -> { need(method, "GET"); json(ex, 200, listResources()); }
            case "/actions" -> { need(method, "GET"); json(ex, 200, listActions()); }
            case "/events" -> { need(method, "GET"); json(ex, 200, Json.GSON.toJsonTree(ctx.events.knownTypes())); }
            case "/events/stream" -> { need(method, "GET"); stream(ex); }
            case "/capabilities" -> { need(method, "GET"); json(ex, 200, onGameThread(() -> Json.GSON.toJsonTree(ctx.capabilities.get()))); }
            case "/mods" -> { need(method, "GET"); json(ex, 200, onGameThread(() -> Json.GSON.toJsonTree(ctx.mods.get()))); }
            case "/schema" -> { need(method, "GET"); json(ex, 200, schemas()); }
            case "/openapi.json" -> { need(method, "GET"); json(ex, 200, OpenApi.document(ctx)); }
            case "/actions/execute" -> { need(method, "POST"); execute(ex); }
            default -> {
                if (rest.startsWith("/resources/")) { need(method, "GET"); resource(ex, rest.substring("/resources/".length())); }
                else if (rest.startsWith("/operations/")) operation(ex, method, rest.substring("/operations/".length()));
                else throw ApiException.notFound("Unknown path " + path);
            }
        }
    }

    private static void need(String actual, String expected) {
        if (!expected.equals(actual)) throw new ApiException(405, "method_not_allowed", "Use " + expected);
    }

    // ---- handlers ----

    private JsonObject index() {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Minecraft Universal API");
        o.addProperty("version", "0.1.0");
        o.addProperty("platform", ctx.platform);
        List<String> links = List.of("resources", "actions", "events", "events/stream", "capabilities", "mods", "schema", "openapi.json");
        JsonObject l = new JsonObject();
        for (String s : links) l.addProperty(s, P + "/" + s);
        o.add("links", l);
        return o;
    }

    private JsonObject listResources() {
        JsonObject o = new JsonObject();
        for (ResourceDef r : sorted(ctx.resources.all(), ResourceDef::id)) {
            JsonObject e = new JsonObject();
            e.addProperty("description", r.description());
            e.addProperty("availability", r.availability().name());
            e.addProperty("href", P + "/resources/" + r.id());
            o.add(r.id(), e);
        }
        return o;
    }

    private JsonObject listActions() {
        JsonObject o = new JsonObject();
        for (ActionDef a : sorted(ctx.actions.all(), ActionDef::name)) {
            JsonObject e = new JsonObject();
            e.addProperty("description", a.description());
            e.addProperty("availability", a.availability().name());
            e.addProperty("permission", a.permission());
            e.add("parameters", OpenApi.actionSchema(a));
            o.add(a.name(), e);
        }
        return o;
    }

    private JsonObject schemas() {
        JsonObject o = new JsonObject();
        for (ActionDef a : ctx.actions.all()) o.add("action:" + a.name(), OpenApi.actionSchema(a));
        return o;
    }

    private void resource(HttpExchange ex, String id) throws Exception {
        ResourceDef r = ctx.resources.get(id);
        if (r == null) throw ApiException.notFound("Unknown resource " + id);
        Params q = new Params(queryParams(ex));
        JsonElement value = onGameThread(() -> Json.GSON.toJsonTree(r.supplier().apply(q)));
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("availability", r.availability().name());
        o.add("value", value);
        json(ex, 200, o);
    }

    private void execute(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        if (!body.has("action")) throw ApiException.badRequest("Missing 'action'");
        String name = body.get("action").getAsString();
        ActionDef a = ctx.actions.get(name);
        if (a == null) throw ApiException.notFound("Unknown action " + name);
        JsonObject args = body.has("parameters") && body.get("parameters").isJsonObject()
                ? body.getAsJsonObject("parameters") : new JsonObject();
        validate(a, args);
        Params p = new Params(args);
        JsonElement result = onGameThread(() -> {
            Object r = a.handler().apply(p);
            if (r instanceof LongRunning lr) {
                Operations.Op op = ctx.operations.start(name, lr);
                JsonObject h = new JsonObject();
                h.addProperty("operation", op.id);
                h.addProperty("status", "/api/v1/operations/" + op.id);
                return h;
            }
            return Json.GSON.toJsonTree(r);
        });
        JsonObject o = new JsonObject();
        o.addProperty("action", name);
        o.add("result", result);
        json(ex, 200, o);
    }

    private void validate(ActionDef a, JsonObject args) {
        List<String> known = new ArrayList<>();
        for (ParamDef p : a.params()) {
            known.add(p.name());
            if (p.required() && (!args.has(p.name()) || args.get(p.name()).isJsonNull()))
                throw ApiException.badRequest("Missing required parameter: " + p.name());
            if (args.has(p.name()) && !typeOk(p.type(), args.get(p.name())))
                throw ApiException.badRequest("Parameter '" + p.name() + "' must be of type " + p.type());
            if (p.enumValues() != null && args.has(p.name()) && !p.enumValues().contains(args.get(p.name()).getAsString()))
                throw ApiException.badRequest("Parameter '" + p.name() + "' must be one of " + p.enumValues());
        }
        for (String k : args.keySet())
            if (!known.contains(k)) throw ApiException.badRequest("Unknown parameter: " + k);
    }

    private static boolean typeOk(String type, JsonElement e) {
        if (e.isJsonNull()) return true;
        return switch (type) {
            case "string" -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
            case "number", "integer" -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
            case "boolean" -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean();
            case "object" -> e.isJsonObject();
            case "array" -> e.isJsonArray();
            default -> true;
        };
    }

    private void operation(HttpExchange ex, String method, String id) throws Exception {
        Operations.Op op = ctx.operations.get(id);
        if (op == null) throw ApiException.notFound("Unknown operation " + id);
        if (method.equals("DELETE")) {
            boolean cancelled = onGameThread(() -> Json.GSON.toJsonTree(ctx.operations.cancel(id))).getAsBoolean();
            if (!cancelled && op.state == Operations.State.RUNNING) throw ApiException.unavailable("Could not cancel");
        } else need(method, "GET");
        JsonObject o = new JsonObject();
        o.addProperty("id", op.id);
        o.addProperty("action", op.action);
        o.addProperty("state", op.state.name());
        if (op.result != null) o.add("result", Json.GSON.toJsonTree(op.result));
        if (op.error != null) o.addProperty("error", op.error);
        json(ex, 200, o);
    }

    /** Server-Sent Events; ends when the client disconnects. */
    private void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        var queue = new java.util.concurrent.LinkedBlockingQueue<EventBus.Event>(1024);
        Runnable unsub = ctx.events.subscribe(queue::offer);
        try {
            while (true) {
                EventBus.Event e = queue.poll(15, TimeUnit.SECONDS);
                String frame;
                if (e == null) frame = ": keep-alive\n\n";
                else {
                    JsonObject o = new JsonObject();
                    o.addProperty("type", e.type());
                    o.addProperty("time", e.timeMillis());
                    o.add("data", e.data());
                    frame = "event: " + e.type() + "\ndata: " + Json.GSON.toJson(o) + "\n\n";
                }
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
        } finally {
            unsub.run();
        }
    }

    // ---- helpers ----

    private <T extends JsonElement> T onGameThread(java.util.concurrent.Callable<T> task) throws Exception {
        try {
            return ctx.gameThread.submit(task).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException ae) throw ae;
            throw new ApiException(500, "handler_error", String.valueOf(e.getCause()));
        } catch (TimeoutException e) {
            throw new ApiException(503, "game_thread_busy", "Game thread did not respond (is a world loaded / game paused?)");
        }
    }

    private static <T> List<T> sorted(java.util.Collection<T> c, java.util.function.Function<T, String> key) {
        List<T> l = new ArrayList<>(c);
        l.sort(java.util.Comparator.comparing(key));
        return l;
    }

    private static JsonObject queryParams(HttpExchange ex) {
        JsonObject o = new JsonObject();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return o;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = java.net.URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            try { o.add(k, JsonParser.parseString(v)); } catch (RuntimeException e) { o.addProperty(k, v); }
            if (o.get(k).isJsonNull() || (o.get(k).isJsonPrimitive() && v.isEmpty())) o.addProperty(k, v);
        }
        return o;
    }

    private static JsonObject readBody(HttpExchange ex) throws IOException {
        String s = new String(ex.getRequestBody().readNBytes(1 << 20), StandardCharsets.UTF_8);
        if (s.isBlank()) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(s);
            if (!e.isJsonObject()) throw ApiException.badRequest("Body must be a JSON object");
            return e.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw ApiException.badRequest("Invalid JSON: " + e.getMessage());
        }
    }

    private static void json(HttpExchange ex, int status, JsonElement body) throws IOException {
        byte[] b = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    private static void error(HttpExchange ex, int status, String code, String msg) throws IOException {
        JsonObject o = new JsonObject();
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", msg);
        o.add("error", e);
        json(ex, status, o);
    }
}

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded HTTP server. Loopback-only by default. Two bearer tokens: FULL (read + actions) and READ
 * (read only). It keeps no per-request state: reads return what the game says now, actions do the
 * thing and return what it returned.
 */
public final class ApiServer {
    private static final String P = "/api/v1";

    private final ApiContext ctx;
    private final Tokens tokens;
    private final Limits limits;
    private final Semaphore concurrent;
    private final AtomicInteger eventStreams = new AtomicInteger();
    private boolean loopbackOnly = true;
    private HttpServer http;

    public ApiServer(ApiContext ctx, Tokens tokens, Limits limits) {
        this.ctx = ctx;
        this.tokens = tokens;
        this.limits = limits;
        this.concurrent = new Semaphore(limits.maxConcurrentRequests());
    }

    /** Loopback only. */
    public int start(int port) throws IOException { return start(InetAddress.getLoopbackAddress(), port); }

    /** Binding to a non-loopback address is an explicit opt-in to remote access. */
    public int start(InetAddress bind, int port) throws IOException {
        loopbackOnly = bind.isLoopbackAddress();
        http = HttpServer.create(new InetSocketAddress(bind, port), 0);
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

    // ---- request pipeline ----

    private void handle(HttpExchange ex) throws IOException {
        boolean acquired = false;
        try {
            if (loopbackOnly && !hostIsLocal(ex)) throw new ApiException(403, "PERMISSION_DENIED", "Unexpected Host header");
            Tokens.Access access = authenticate(ex);
            boolean isStream = ex.getRequestURI().getPath().equals(P + "/events/stream");
            if (!isStream) {
                if (!concurrent.tryAcquire()) throw new ApiException(429, "RATE_LIMITED", "Too many concurrent requests");
                acquired = true;
            }
            route(ex, access, ex.getRequestMethod(), ex.getRequestURI().getPath());
        } catch (ApiException e) {
            error(ex, e.status, e.code, e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "INTERNAL_ERROR", String.valueOf(e.getMessage()));
        } finally {
            if (acquired) concurrent.release();
            ex.close();
        }
    }

    private static boolean hostIsLocal(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Host");
        if (h == null) return false;
        String host = h.startsWith("[") ? h.substring(0, h.indexOf(']') + 1) : h.split(":")[0];
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
    }

    private Tokens.Access authenticate(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer "))
            throw new ApiException(401, "UNAUTHORIZED", "Missing or invalid bearer token");
        return tokens.authenticate(h.substring(7))
                .orElseThrow(() -> new ApiException(401, "UNAUTHORIZED", "Missing or invalid bearer token"));
    }

    private void route(HttpExchange ex, Tokens.Access access, String method, String path) throws Exception {
        if (!path.equals(P) && !path.startsWith(P + "/")) throw ApiException.notFound("Unknown path " + path);
        String rest = path.substring(P.length());
        switch (rest.isEmpty() ? "/" : rest) {
            case "/" -> { need(method, "GET"); json(ex, 200, index()); }
            case "/resources" -> { need(method, "GET"); json(ex, 200, listResources()); }
            case "/actions" -> { need(method, "GET"); json(ex, 200, listActions(access)); }
            case "/events" -> { need(method, "GET"); json(ex, 200, Json.GSON.toJsonTree(ctx.events.knownTypes())); }
            case "/events/stream" -> { need(method, "GET"); stream(ex); }
            case "/capabilities" -> { need(method, "GET"); json(ex, 200, onGameThread(() -> Json.GSON.toJsonTree(ctx.capabilitiesDocument()))); }
            case "/mods" -> { need(method, "GET"); json(ex, 200, onGameThread(() -> Json.GSON.toJsonTree(ctx.mods.get()))); }
            case "/schema" -> { need(method, "GET"); json(ex, 200, schemas()); }
            case "/openapi.json" -> { need(method, "GET"); json(ex, 200, OpenApi.document(ctx)); }
            case "/actions/execute" -> { need(method, "POST"); execute(ex, access); }
            default -> {
                if (rest.startsWith("/resources/")) { need(method, "GET"); resource(ex, rest.substring("/resources/".length())); }
                else throw ApiException.notFound("Unknown path " + path);
            }
        }
    }

    private static void need(String actual, String expected) {
        if (!expected.equals(actual)) throw new ApiException(405, "METHOD_NOT_ALLOWED", "Use " + expected);
    }

    // ---- discovery ----

    private JsonObject index() {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Minecraft Universal API");
        o.addProperty("version", "0.1.0");
        o.addProperty("platform", ctx.platform);
        JsonObject l = new JsonObject();
        for (String s : List.of("resources", "actions", "actions/execute", "events", "events/stream",
                "capabilities", "mods", "schema", "openapi.json"))
            l.addProperty(s, P + "/" + s);
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

    private JsonObject listActions(Tokens.Access access) {
        JsonObject o = new JsonObject();
        for (ActionDef a : sorted(ctx.actions.all(), ActionDef::name)) {
            JsonObject e = new JsonObject();
            e.addProperty("description", a.description());
            e.addProperty("availability", a.availability().name());
            e.addProperty("permitted", access == Tokens.Access.FULL);
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

    // ---- resources ----

    private void resource(HttpExchange ex, String id) throws Exception {
        ResourceDef r = ctx.resources.get(id);
        if (r == null) throw ApiException.notFound("Unknown resource " + id);
        Params q = new Params(queryParams(ex));
        // The tick is read in the same game-thread task as the value, so they describe the same moment.
        JsonObject res = onGameThread(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("success", true);
            o.addProperty("resource", id);
            o.addProperty("availability", r.availability().name());
            o.addProperty("source", "client");
            long tick = ctx.observedTick.getAsLong();
            if (tick >= 0) o.addProperty("observedTick", tick);
            o.add("value", Json.GSON.toJsonTree(r.supplier().apply(q)));
            return o;
        });
        json(ex, 200, res);
    }

    // ---- actions ----

    private void execute(HttpExchange ex, Tokens.Access access) throws Exception {
        if (access != Tokens.Access.FULL)
            throw new ApiException(403, "PERMISSION_DENIED", "This token is read-only; use the full-control token to run actions");
        JsonObject body = readBody(ex);
        if (!body.has("action") || !body.get("action").isJsonPrimitive()) throw ApiException.badRequest("Missing 'action'");
        String name = body.get("action").getAsString();
        ActionDef a = ctx.actions.get(name);
        if (a == null) throw ApiException.notFound("Unknown action " + name);
        JsonObject args = new JsonObject();
        if (body.has("parameters")) {
            if (!body.get("parameters").isJsonObject()) throw ApiException.badRequest("'parameters' must be an object");
            args = body.getAsJsonObject("parameters");
        }
        validate(a, args);
        Params p = new Params(args);
        ctx.log.accept("action " + name + " " + args);
        JsonObject res = onGameThread(() -> {
            String blocked = ctx.controlBlock.get();
            if (blocked != null) throw ApiException.unavailable(blocked);
            Object result = a.handler().apply(p);
            JsonObject o = new JsonObject();
            o.addProperty("success", true);
            o.addProperty("action", name);
            o.add("result", Json.GSON.toJsonTree(result));
            return o;
        });
        json(ex, 200, res);
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

    // ---- events ----

    /** Server-Sent Events; ends when the client disconnects. Optional ?types=a,b filter. */
    private void stream(HttpExchange ex) throws IOException {
        if (eventStreams.incrementAndGet() > limits.maxEventStreams()) {
            eventStreams.decrementAndGet();
            throw new ApiException(429, "RATE_LIMITED", "Too many event streams");
        }
        Set<String> filter = new HashSet<>();
        String types = new Params(queryParams(ex)).string("types", "");
        for (String t : types.split(",")) if (!t.isBlank()) filter.add(t.trim());
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        var queue = new java.util.concurrent.LinkedBlockingQueue<EventBus.Event>(1024);
        Runnable unsub = ctx.events.subscribe(e -> { if (filter.isEmpty() || filter.contains(e.type())) queue.offer(e); });
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
            eventStreams.decrementAndGet();
        }
    }

    // ---- helpers ----

    /**
     * Runs the task on the game thread and waits. If the game thread picks the task up after this
     * request has already timed out, the task refuses to run, so a 503 means "nothing was done".
     */
    private <T extends JsonElement> T onGameThread(Callable<T> task) throws Exception {
        long timeoutMs = limits.gameThreadTimeoutMs();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        try {
            return ctx.gameThread.submit(() -> {
                if (System.nanoTime() > deadline)
                    throw new ApiException(503, "GAME_THREAD_BUSY", "Request expired before the game thread ran it; nothing was done");
                return task.call();
            }).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException ae) throw ae;
            throw new ApiException(500, "ACTION_FAILED", String.valueOf(e.getCause()));
        } catch (TimeoutException e) {
            throw new ApiException(503, "GAME_THREAD_BUSY", "Game thread did not respond (is the game paused or still loading?)");
        }
    }

    private static JsonObject failure(String code, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("success", false);
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", msg);
        o.add("error", e);
        return o;
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
            // Numbers and booleans become typed values; everything else stays a string.
            if (v.matches("-?\\d+(\\.\\d+)?") || v.equals("true") || v.equals("false")) o.add(k, JsonParser.parseString(v));
            else o.addProperty(k, v);
        }
        return o;
    }

    private JsonObject readBody(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(limits.maxRequestBytes() + 1);
        if (raw.length > limits.maxRequestBytes()) throw new ApiException(413, "REQUEST_TOO_LARGE", "Request body too large");
        String s = new String(raw, StandardCharsets.UTF_8);
        if (s.isBlank()) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(s);
            if (!e.isJsonObject()) throw ApiException.badRequest("Body must be a JSON object");
            return e.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw ApiException.badRequest("Invalid JSON: " + e.getMessage());
        }
    }

    private void json(HttpExchange ex, int status, JsonElement body) throws IOException {
        byte[] b = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        if (b.length > limits.maxResponseBytes()) {
            error(ex, 413, "RESPONSE_TOO_LARGE", "Response exceeds " + limits.maxResponseBytes() + " bytes; narrow the query or paginate");
            return;
        }
        write(ex, status, b);
    }

    private void error(HttpExchange ex, int status, String code, String msg) throws IOException {
        write(ex, status, Json.GSON.toJson(failure(code, msg)).getBytes(StandardCharsets.UTF_8));
    }

    private static void write(HttpExchange ex, int status, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}

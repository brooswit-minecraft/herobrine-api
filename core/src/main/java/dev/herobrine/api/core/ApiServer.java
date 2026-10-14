package dev.herobrine.api.core;

import com.google.gson.JsonArray;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded HTTP server. Defaults to loopback-only; every request needs a scoped bearer token.
 * All game-state access is marshalled through {@link GameThread}.
 */
public final class ApiServer {
    private static final String P = "/api/v1";

    private final ApiContext ctx;
    private final TokenStore tokens;
    private final Limits limits;
    private final Semaphore concurrent;
    private final AtomicInteger eventStreams = new AtomicInteger();
    private final Map<String, long[]> rateWindows = new ConcurrentHashMap<>(); // token id -> {second, count}
    private boolean loopbackOnly = true;
    private HttpServer http;

    public ApiServer(ApiContext ctx, TokenStore tokens, Limits limits) {
        this.ctx = ctx;
        this.tokens = tokens;
        this.limits = limits;
        this.concurrent = new Semaphore(limits.maxConcurrentRequests());
    }

    /** Loopback only. */
    public int start(int port) throws IOException { return start(InetAddress.getLoopbackAddress(), port); }

    /** Binding to a non-loopback address is an explicit opt-in to remote access (spec 12.1/12.4). */
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
            TokenStore.Token token = authenticate(ex);
            enforceRate(token);
            boolean isStream = ex.getRequestURI().getPath().equals(P + "/events/stream");
            if (!isStream) {
                if (!concurrent.tryAcquire()) throw new ApiException(429, "RATE_LIMITED", "Too many concurrent requests");
                acquired = true;
            }
            route(ex, token, ex.getRequestMethod(), ex.getRequestURI().getPath());
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

    private TokenStore.Token authenticate(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer "))
            throw new ApiException(401, "UNAUTHORIZED", "Missing or invalid bearer token");
        return tokens.authenticate(h.substring(7))
                .orElseThrow(() -> new ApiException(401, "UNAUTHORIZED", "Missing or invalid bearer token"));
    }

    private void enforceRate(TokenStore.Token t) {
        long sec = System.currentTimeMillis() / 1000;
        long[] w = rateWindows.computeIfAbsent(t.id(), k -> new long[2]);
        synchronized (w) {
            if (w[0] != sec) { w[0] = sec; w[1] = 0; }
            if (++w[1] > limits.maxRequestsPerSecondPerToken())
                throw new ApiException(429, "RATE_LIMITED", "Rate limit exceeded");
        }
    }

    private static void require(TokenStore.Token t, String permission) {
        if (!t.allows(permission)) throw new ApiException(403, "PERMISSION_DENIED", "Token lacks permission '" + permission + "'");
    }

    private void route(HttpExchange ex, TokenStore.Token tok, String method, String path) throws Exception {
        if (!path.equals(P) && !path.startsWith(P + "/")) throw ApiException.notFound("Unknown path " + path);
        String rest = path.substring(P.length());
        switch (rest.isEmpty() ? "/" : rest) {
            case "/" -> { need(method, "GET"); json(ex, 200, index()); }
            case "/resources" -> { need(method, "GET"); json(ex, 200, listResources(tok)); }
            case "/actions" -> { need(method, "GET"); json(ex, 200, listActions(tok)); }
            case "/events" -> { need(method, "GET"); json(ex, 200, Json.GSON.toJsonTree(ctx.events.knownTypes())); }
            case "/events/stream" -> { need(method, "GET"); require(tok, "read.client"); stream(ex); }
            case "/capabilities" -> { need(method, "GET"); json(ex, 200, onGameThread(() -> Json.GSON.toJsonTree(ctx.capabilitiesDocument()))); }
            case "/mods" -> { need(method, "GET"); json(ex, 200, onGameThread(() -> Json.GSON.toJsonTree(ctx.mods.get()))); }
            case "/schema" -> { need(method, "GET"); json(ex, 200, schemas()); }
            case "/openapi.json" -> { need(method, "GET"); json(ex, 200, OpenApi.document(ctx)); }
            case "/actions/execute" -> { need(method, "POST"); execute(ex, tok); }
            case "/actions/batch" -> { need(method, "POST"); batch(ex, tok); }
            case "/operations" -> { need(method, "GET"); listOperations(ex, tok); }
            case "/admin/tokens" -> adminTokens(ex, tok, method);
            case "/admin/audit" -> { need(method, "GET"); require(tok, "admin.api"); json(ex, 200, Json.GSON.toJsonTree(ctx.audit.recent())); }
            default -> {
                if (rest.startsWith("/resources/")) { need(method, "GET"); resource(ex, tok, rest.substring("/resources/".length())); }
                else if (rest.startsWith("/operations/")) operation(ex, tok, method, rest.substring("/operations/".length()));
                else if (rest.startsWith("/admin/tokens/")) {
                    need(method, "DELETE");
                    require(tok, "admin.api");
                    String id = rest.substring("/admin/tokens/".length());
                    if (!tokens.revoke(id)) throw ApiException.notFound("Unknown token " + id);
                    ctx.audit.record(tok.id(), "admin", "revoked token " + id);
                    json(ex, 200, ok(Map.of("revoked", id)));
                } else throw ApiException.notFound("Unknown path " + path);
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
        for (String s : List.of("resources", "actions", "actions/execute", "actions/batch", "operations", "events", "events/stream",
                "capabilities", "mods", "schema", "openapi.json"))
            l.addProperty(s, P + "/" + s);
        o.add("links", l);
        return o;
    }

    private JsonObject listResources(TokenStore.Token t) {
        JsonObject o = new JsonObject();
        for (ResourceDef r : sorted(ctx.resources.all(), ResourceDef::id)) {
            JsonObject e = new JsonObject();
            e.addProperty("description", r.description());
            e.addProperty("availability", r.availability().name());
            e.addProperty("permission", r.permission());
            e.addProperty("permitted", t.allows(r.permission()));
            e.addProperty("href", P + "/resources/" + r.id());
            o.add(r.id(), e);
        }
        return o;
    }

    private JsonObject listActions(TokenStore.Token t) {
        JsonObject o = new JsonObject();
        for (ActionDef a : sorted(ctx.actions.all(), ActionDef::name)) {
            JsonObject e = new JsonObject();
            e.addProperty("description", a.description());
            e.addProperty("availability", a.availability().name());
            e.addProperty("permission", a.permission());
            e.addProperty("permitted", t.allows(a.permission()));
            e.add("locks", Json.GSON.toJsonTree(a.locks()));
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

    private void resource(HttpExchange ex, TokenStore.Token tok, String id) throws Exception {
        ResourceDef r = ctx.resources.get(id);
        if (r == null) throw ApiException.notFound("Unknown resource " + id);
        require(tok, r.permission());
        Params q = new Params(queryParams(ex));
        JsonElement value = onGameThread(() -> Json.GSON.toJsonTree(r.supplier().apply(q)));
        JsonObject o = new JsonObject();
        o.addProperty("success", true);
        o.addProperty("resource", id);
        o.addProperty("availability", r.availability().name());
        o.addProperty("source", "client");
        long tick = ctx.observedTick.getAsLong();
        if (tick >= 0) o.addProperty("observedTick", tick);
        o.add("value", value);
        json(ex, 200, o);
    }

    // ---- actions ----

    private ActionDef lookup(JsonObject body, TokenStore.Token tok) {
        if (!body.has("action")) throw ApiException.badRequest("Missing 'action'");
        String name = body.get("action").getAsString();
        ActionDef a = ctx.actions.get(name);
        if (a == null) throw ApiException.notFound("Unknown action " + name);
        require(tok, a.permission());
        return a;
    }

    private static JsonObject argsOf(JsonObject body) {
        return body.has("parameters") && body.get("parameters").isJsonObject() ? body.getAsJsonObject("parameters") : new JsonObject();
    }

    private static int intField(JsonObject o, String k, int def) {
        try { return o.has(k) ? o.get(k).getAsInt() : def; }
        catch (RuntimeException e) { throw ApiException.badRequest("'" + k + "' must be a number"); }
    }

    private JsonObject outcomeJson(String action, ActionExecutor.Outcome out) {
        JsonObject o = new JsonObject();
        o.addProperty("success", true);
        o.addProperty("action", action);
        o.addProperty("stage", out.stage().name());
        if (out.op() != null) {
            JsonObject h = new JsonObject();
            h.addProperty("operationId", out.op().id);
            h.addProperty("status", out.op().state.name().toLowerCase());
            h.addProperty("action", action);
            h.addProperty("href", P + "/operations/" + out.op().id);
            o.add("result", h);
        } else o.add("result", Json.GSON.toJsonTree(out.result()));
        return o;
    }

    private void execute(HttpExchange ex, TokenStore.Token tok) throws Exception {
        JsonObject body = readBody(ex);
        ActionDef a = lookup(body, tok);
        JsonObject args = argsOf(body);
        validate(a, args);
        int delay = intField(body, "delayTicks", 0);
        long timeout = intField(body, "timeoutMs", 0);
        if (delay < 0) throw ApiException.badRequest("'delayTicks' must be >= 0");
        Params p = new Params(args);
        ctx.audit.record(tok.id(), "action", a.name() + " " + args);
        JsonObject res = onGameThread(() -> outcomeJson(a.name(), ctx.executor.run(a, p, tok.id(), delay, timeout)));
        json(ex, 200, res);
    }

    private void batch(HttpExchange ex, TokenStore.Token tok) throws Exception {
        JsonObject body = readBody(ex);
        if (!body.has("actions") || !body.get("actions").isJsonArray()) throw ApiException.badRequest("Missing 'actions' array");
        JsonArray arr = body.getAsJsonArray("actions");
        if (arr.size() > limits.maxBatchSize()) throw ApiException.badRequest("Batch exceeds " + limits.maxBatchSize() + " actions");
        String mode = body.has("mode") ? body.get("mode").getAsString() : "sequential";
        if (!mode.equals("sequential") && !mode.equals("concurrent")) throw ApiException.badRequest("'mode' must be sequential or concurrent");
        boolean stopOnError = !body.has("stopOnError") || body.get("stopOnError").getAsBoolean();
        long timeout = intField(body, "timeoutMs", 0);

        List<ActionExecutor.Step> steps = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) throw ApiException.badRequest("Each batch entry must be an object");
            JsonObject item = e.getAsJsonObject();
            ActionDef a = lookup(item, tok);
            validate(a, argsOf(item));
            steps.add(new ActionExecutor.Step(a, new Params(argsOf(item)), Math.max(0, intField(item, "delayTicks", 0))));
        }
        ctx.audit.record(tok.id(), "batch", mode + " x" + steps.size());

        JsonObject res = onGameThread(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("success", true);
            o.addProperty("mode", mode);
            if (mode.equals("sequential")) {
                Operations.Op op = ctx.executor.runSequence(steps, tok.id(), timeout, stopOnError);
                o.addProperty("stage", Stage.ACCEPTED.name());
                JsonObject h = new JsonObject();
                h.addProperty("operationId", op.id);
                h.addProperty("status", "running");
                h.addProperty("href", P + "/operations/" + op.id);
                o.add("result", h);
            } else {
                JsonArray results = new JsonArray();
                for (ActionExecutor.Step s : steps) {
                    try {
                        results.add(outcomeJson(s.action().name(),
                                ctx.executor.run(s.action(), s.params(), tok.id(), s.delayTicks(), timeout)));
                    } catch (ApiException | IllegalStateException e) {
                        results.add(failure(s.action().name(), e instanceof ApiException ae ? ae.code : "ACTION_FAILED", e.getMessage()));
                    }
                }
                o.add("results", results);
            }
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

    // ---- operations ----

    private JsonObject describe(Operations.Op op) {
        JsonObject o = new JsonObject();
        o.addProperty("operationId", op.id);
        o.addProperty("action", op.action);
        o.addProperty("owner", op.owner);
        o.addProperty("status", op.state.name().toLowerCase());
        o.addProperty("createdAt", op.createdAtMillis);
        if (op.finishedAtMillis != 0) o.addProperty("finishedAt", op.finishedAtMillis);
        if (op.progress != null) o.add("progress", Json.GSON.toJsonTree(op.progress));
        if (op.result != null) o.add("result", Json.GSON.toJsonTree(ActionExecutor.unwrap(op.result)));
        if (op.error != null) o.addProperty("error", op.error);
        o.addProperty("href", P + "/operations/" + op.id);
        return o;
    }

    private void listOperations(HttpExchange ex, TokenStore.Token tok) throws IOException {
        boolean admin = tok.allows("admin.api");
        Params q = new Params(queryParams(ex));
        String status = q.string("status", null);
        List<JsonObject> list = new ArrayList<>();
        for (Operations.Op op : ctx.operations.list()) {
            if (!admin && !op.owner.equals(tok.id())) continue;
            if (status != null && !op.state.name().equalsIgnoreCase(status)) continue;
            list.add(describe(op));
        }
        Page<JsonObject> page = Page.of(list, q, 100, limits.maxPageSize());
        json(ex, 200, Json.GSON.toJsonTree(page));
    }

    private void operation(HttpExchange ex, TokenStore.Token tok, String method, String rest) throws Exception {
        boolean cancel = rest.endsWith("/cancel");
        String id = cancel ? rest.substring(0, rest.length() - "/cancel".length()) : rest;
        Operations.Op op = ctx.operations.get(id);
        if (op == null) throw ApiException.notFound("Unknown operation " + id);
        if (!op.owner.equals(tok.id()) && !tok.allows("admin.api"))
            throw new ApiException(403, "PERMISSION_DENIED", "Operation belongs to another controller");
        if (cancel || method.equals("DELETE")) {
            if (cancel) need(method, "POST");
            ctx.audit.record(tok.id(), "cancel", id);
            onGameThread(() -> Json.GSON.toJsonTree(ctx.operations.cancel(id)));
        } else need(method, "GET");
        JsonObject o = describe(op);
        o.addProperty("success", true);
        json(ex, 200, o);
    }

    // ---- admin ----

    private void adminTokens(HttpExchange ex, TokenStore.Token tok, String method) throws IOException {
        require(tok, "admin.api");
        if (method.equals("GET")) {
            json(ex, 200, Json.GSON.toJsonTree(tokens.list()));
        } else if (method.equals("POST")) {
            JsonObject b = readBody(ex);
            if (!b.has("name") || !b.has("permissions") || !b.get("permissions").isJsonArray())
                throw ApiException.badRequest("Need 'name' and 'permissions' (array)");
            Set<String> perms = new HashSet<>();
            b.getAsJsonArray("permissions").forEach(e -> perms.add(e.getAsString()));
            TokenStore.Created c = tokens.create(b.get("name").getAsString(), perms);
            ctx.audit.record(tok.id(), "admin", "created token " + c.token().id() + " " + perms);
            JsonObject o = new JsonObject();
            o.addProperty("success", true);
            o.add("token", Json.GSON.toJsonTree(c.token()));
            o.addProperty("secret", c.secret()); // shown once
            json(ex, 200, o);
        } else throw new ApiException(405, "METHOD_NOT_ALLOWED", "Use GET or POST");
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

    private <T extends JsonElement> T onGameThread(Callable<T> task) throws Exception {
        try {
            return ctx.gameThread.submit(task).get(limits.gameThreadTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException ae) throw ae;
            throw new ApiException(500, "ACTION_FAILED", String.valueOf(e.getCause()));
        } catch (TimeoutException e) {
            throw new ApiException(503, "GAME_THREAD_BUSY", "Game thread did not respond (is the game paused or still loading?)");
        }
    }

    private static JsonObject ok(Object result) {
        JsonObject o = new JsonObject();
        o.addProperty("success", true);
        o.add("result", Json.GSON.toJsonTree(result));
        return o;
    }

    private static JsonObject failure(String action, String code, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("success", false);
        if (action != null) o.addProperty("action", action);
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
        write(ex, status, Json.GSON.toJson(failure(null, code, msg)).getBytes(StandardCharsets.UTF_8));
    }

    private static void write(HttpExchange ex, int status, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}

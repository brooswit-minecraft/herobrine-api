package dev.herobrine.api.core.worldedit;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import dev.herobrine.api.core.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * The binding end to end: the real {@code ApiServer} with real tokens, the registered {@code worldedit.*}
 * definitions, the real {@link ReflectiveWorldEdit} against the {@code com.sk89q.worldedit} test doubles, and
 * an actor scope shaped like the client's: requests arrive on a "client" game thread and hop to a separate
 * "server" thread that owns the world.
 */
class WorldEditBindingTest {
    static final String CLIENT_THREAD = "fake-client-thread", SERVER_THREAD = "fake-server-thread";
    static final ExecutorService client = Executors.newSingleThreadExecutor(r -> new Thread(r, CLIENT_THREAD));
    static final ExecutorService server = Executors.newSingleThreadExecutor(r -> new Thread(r, SERVER_THREAD));
    static final HttpClient http = HttpClient.newHttpClient();
    static final AtomicLong maxEditVolume = new AtomicLong(1_000);
    static final Map<String, FakeActor> actors = new ConcurrentHashMap<>();
    static final List<String> workThreads = new ArrayList<>();
    static ApiServer api, absentApi;
    static String base, absentBase, full, read;
    @TempDir static Path tmp;

    static GameThread on(ExecutorService ex) {
        return new GameThread() {
            public <T> CompletableFuture<T> submit(Callable<T> task) {
                CompletableFuture<T> f = new CompletableFuture<>();
                ex.execute(() -> { try { f.complete(task.call()); } catch (Throwable t) { f.completeExceptionally(t); } });
                return f;
            }
        };
    }

    /** Resolves a named player and runs the work on the server thread, like the platform scopes do. */
    static final ActorScope SCOPE = new ActorScope() {
        @Override public List<ParamDef> params() {
            return List.of(ParamDef.required("player", "string", "Player to act as"));
        }
        @Override public <T> T call(Params p, Function<Object, T> work) {
            String name = p.string("player");
            return GameThreadHop.call(on(server), Thread.currentThread().getName().equals(SERVER_THREAD), 2_000, 5_000, () -> {
                synchronized (workThreads) { workThreads.add(Thread.currentThread().getName()); }
                return work.apply(actors.computeIfAbsent(name, FakeActor::new));
            });
        }
    };

    @BeforeAll static void start() throws Exception {
        Tokens tokens = Tokens.loadOrCreate(tmp.resolve("full.token"), tmp.resolve("read.token"));
        full = Files.readString(tokens.fullFile()).trim();
        read = Files.readString(tokens.readFile()).trim();

        ApiContext ctx = new ApiContext(on(client));
        WorldEditBinding.register(ctx, new ReflectiveWorldEdit(WorldEditBindingTest.class.getClassLoader()), SCOPE, maxEditVolume::get);
        api = new ApiServer(ctx, tokens, Limits.defaults());
        base = "http://127.0.0.1:" + api.start(0) + "/api/v1";

        ApiContext absent = new ApiContext(on(client));
        WorldEditBinding.register(absent, new ReflectiveWorldEdit(new URLClassLoader(new URL[0], null)), SCOPE, maxEditVolume::get);
        absentApi = new ApiServer(absent, tokens, Limits.defaults());
        absentBase = "http://127.0.0.1:" + absentApi.start(0) + "/api/v1";
    }

    @AfterAll static void stop() {
        api.stop();
        absentApi.stop();
        client.shutdownNow();
        server.shutdownNow();
    }

    @BeforeEach void reset() {
        FakeWorldEdit.reset();
        FakeWorldEdit.INSTANCE.workingDirectory = tmp.resolve("worldedit");
        actors.clear();
        synchronized (workThreads) { workThreads.clear(); }
        maxEditVolume.set(1_000);
    }

    static HttpResponse<String> send(String root, String method, String path, String body, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(root + path)).header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> exec(String root, String token, String action, String params) throws Exception {
        return send(root, "POST", "/actions/execute", "{\"action\":\"" + action + "\",\"parameters\":" + params + "}", token);
    }

    static JsonObject obj(HttpResponse<String> r) { return JsonParser.parseString(r.body()).getAsJsonObject(); }

    static String code(HttpResponse<String> r) { return obj(r).getAsJsonObject("error").get("code").getAsString(); }

    static LocalSession session(String player) { return WorldEdit.getInstance().getSessionManager().get(new FakeActor(player)); }

    static final String BOX = "\"x1\":0,\"y1\":64,\"z1\":0,\"x2\":1,\"y2\":65,\"z2\":1";

    // ---- discovery ----

    @Test void everySchemaOperationIsDiscoverableWithItsTypedParameters() throws Exception {
        JsonObject actions = obj(send(base, "GET", "/actions", null, read));
        JsonObject resources = obj(send(base, "GET", "/resources", null, read));
        for (WorldEditSchema.Operation op : WorldEditSchema.OPERATIONS) {
            if (WorldEditActions.kind(op.name()) == WorldEditActions.Kind.READ) {
                assertTrue(resources.has(op.name()), op.name());
                assertTrue(resources.getAsJsonObject(op.name()).get("description").getAsString().contains("player (required)"));
                assertFalse(actions.has(op.name()), op.name());
                continue;
            }
            assertTrue(actions.has(op.name()), op.name());
            JsonObject schema = actions.getAsJsonObject(op.name()).getAsJsonObject("parameters");
            Set<String> declared = new HashSet<>(schema.getAsJsonObject("properties").keySet());
            Set<String> expected = new HashSet<>(List.of("player"));
            op.params().forEach(d -> expected.add(d.name()));
            assertEquals(expected, declared, op.name());
        }
        JsonArray required = actions.getAsJsonObject("worldedit.set_blocks").getAsJsonObject("parameters").getAsJsonArray("required");
        Set<String> requiredNames = new HashSet<>();
        required.forEach(e -> requiredNames.add(e.getAsString()));
        assertEquals(Set.of("x1", "y1", "z1", "x2", "y2", "z2", "pattern", "player"), requiredNames);
        assertTrue(obj(send(base, "GET", "/capabilities", null, read)).getAsJsonObject("integrations").get("worldedit").getAsBoolean());
    }

    // ---- authentication ----

    @Test void theReadOnlyTokenCannotEditButCanReadTheSelection() throws Exception {
        var denied = exec(base, read, "worldedit.set_blocks", "{" + BOX + ",\"pattern\":\"stone\",\"player\":\"alex\"}");
        assertEquals(403, denied.statusCode());
        assertEquals("PERMISSION_DENIED", code(denied));
        assertEquals(List.of(), FakeWorldEdit.INSTANCE.calls);

        LocalSession alex = session("alex");
        alex.selectionWorld = new FakeWorld("overworld");
        alex.selectionRegion = new com.sk89q.worldedit.regions.CuboidRegion(
                com.sk89q.worldedit.math.BlockVector3.at(1, 2, 3), com.sk89q.worldedit.math.BlockVector3.at(4, 5, 6));
        var sel = send(base, "GET", "/resources/worldedit.selection?player=alex", null, read);
        assertEquals(200, sel.statusCode(), sel.body());
        JsonObject box = obj(sel).getAsJsonObject("value");
        assertEquals(1, box.get("x1").getAsInt());
        assertEquals(6, box.get("z2").getAsInt());
    }

    // ---- actor context and server thread ----

    @Test void anEditRunsOnTheServerThreadThroughTheRealAdapter() throws Exception {
        var r = exec(base, full, "worldedit.set_blocks", "{" + BOX + ",\"pattern\":\"stone\",\"player\":\"alex\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(7, obj(r).getAsJsonObject("result").get("changed").getAsInt());
        assertTrue(FakeWorldEdit.INSTANCE.calls.contains("setBlocks:CuboidRegion(BV3(0,64,0),BV3(1,65,1)):stone"));
        assertEquals(List.of(SERVER_THREAD), workThreads);
        assertEquals(1, session("alex").history.size());
    }

    @Test void eachPlayerHasTheirOwnClipboardAndHistory() throws Exception {
        assertEquals(200, exec(base, full, "worldedit.copy", "{" + BOX + ",\"player\":\"alex\"}").statusCode());
        var bobPaste = exec(base, full, "worldedit.paste", "{\"x\":0,\"y\":64,\"z\":0,\"player\":\"bob\"}");
        assertEquals(400, bobPaste.statusCode());
        assertTrue(obj(bobPaste).getAsJsonObject("error").get("message").getAsString().contains("No clipboard"));
        assertEquals(200, exec(base, full, "worldedit.paste", "{\"x\":0,\"y\":64,\"z\":0,\"player\":\"alex\"}").statusCode());

        assertEquals(200, exec(base, full, "worldedit.set_blocks", "{" + BOX + ",\"pattern\":\"stone\",\"player\":\"alex\"}").statusCode());
        JsonElement bobUndo = obj(exec(base, full, "worldedit.undo", "{\"player\":\"bob\"}")).getAsJsonObject("result").get("undone");
        assertFalse(bobUndo.getAsBoolean());
        JsonElement alexUndo = obj(exec(base, full, "worldedit.undo", "{\"player\":\"alex\"}")).getAsJsonObject("result").get("undone");
        assertTrue(alexUndo.getAsBoolean());
    }

    @Test void theActorsWorldEditPermissionsApply() throws Exception {
        actors.put("guest", new FakeActor("guest", new FakeWorld("overworld"), Set.of("worldedit.clipboard.copy")));
        var r = exec(base, full, "worldedit.set_blocks", "{" + BOX + ",\"pattern\":\"stone\",\"player\":\"guest\"}");
        assertEquals(403, r.statusCode());
        assertTrue(obj(r).getAsJsonObject("error").get("message").getAsString().contains("worldedit.region.set"));
        assertEquals(200, exec(base, full, "worldedit.copy", "{" + BOX + ",\"player\":\"guest\"}").statusCode());
    }

    @Test void theEditCapIsTheConfiguredMaxEditVolume() throws Exception {
        maxEditVolume.set(7); // BOX is 2 x 2 x 2 = 8
        var r = exec(base, full, "worldedit.set_blocks", "{" + BOX + ",\"pattern\":\"stone\",\"player\":\"alex\"}");
        assertEquals(400, r.statusCode());
        assertEquals(List.of(), FakeWorldEdit.INSTANCE.calls);
    }

    @Test void theSchemaIsEnforcedBeforeAnythingRuns() throws Exception {
        var unknown = exec(base, full, "worldedit.set_blocks", "{" + BOX + ",\"pattern\":\"stone\",\"player\":\"alex\",\"radius\":3}");
        assertEquals(400, unknown.statusCode());
        var noPlayer = exec(base, full, "worldedit.undo", "{}");
        assertEquals(400, noPlayer.statusCode());
        assertEquals(List.of(), workThreads);
    }

    @Test void schematicsRoundTripThroughWorldEditsSchematicsFolder() throws Exception {
        assertEquals(200, exec(base, full, "worldedit.copy", "{" + BOX + ",\"player\":\"alex\"}").statusCode());
        assertEquals(200, exec(base, full, "worldedit.rotate_clipboard", "{\"degreesY\":90,\"player\":\"alex\"}").statusCode());
        var saved = exec(base, full, "worldedit.save_schematic", "{\"file\":\"house.schem\",\"player\":\"alex\"}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(Files.isRegularFile(tmp.resolve("worldedit").resolve("schematics").resolve("house.schem")));
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(c -> c.startsWith("schematic.write:") && c.contains("rotateY(-90.0)")),
                "the pending rotation is baked into the saved schematic, as //schem save does");
        assertEquals(400, exec(base, full, "worldedit.save_schematic", "{\"file\":\"house.schem\",\"player\":\"alex\"}").statusCode());
        assertEquals(200, exec(base, full, "worldedit.load_schematic", "{\"file\":\"house.schem\",\"player\":\"bob\"}").statusCode());
        assertNotNull(session("bob").clipboard);
        assertEquals(400, exec(base, full, "worldedit.load_schematic", "{\"file\":\"../house.schem\",\"player\":\"bob\"}").statusCode());
    }

    // ---- WorldEdit absent ----

    @Test void withoutWorldEditEverythingStaysDiscoverableButUnavailable() throws Exception {
        assertTrue(obj(send(absentBase, "GET", "/actions", null, read)).has("worldedit.set_blocks"));
        assertFalse(obj(send(absentBase, "GET", "/capabilities", null, read)).getAsJsonObject("integrations").get("worldedit").getAsBoolean());
        var r = exec(absentBase, full, "worldedit.set_blocks", "{" + BOX + ",\"pattern\":\"stone\",\"player\":\"alex\"}");
        assertEquals(409, r.statusCode());
        assertEquals("ACTION_UNAVAILABLE", code(r));
        JsonObject value = obj(send(absentBase, "GET", "/resources/worldedit.selection?player=alex", null, read)).getAsJsonObject("value");
        assertFalse(value.get("available").getAsBoolean());
        assertEquals("MOD_NOT_INSTALLED", value.get("reason").getAsString());
        assertEquals(List.of(), workThreads);
    }
}

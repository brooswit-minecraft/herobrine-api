package dev.herobrine.api.neoforge;

import com.google.gson.JsonObject;
import dev.herobrine.api.core.*;
import dev.herobrine.api.core.baritone.BaritoneAdapter;
import dev.herobrine.api.core.baritone.ReflectiveBaritone;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Baritone endpoints. Always registered so they stay discoverable; they report the integration as
 * unavailable when Baritone is absent. Actions call Baritone's typed Java API through
 * {@link BaritoneAdapter}; Baritone's command manager is used only for operations the installed
 * build's API does not provide (or has no typed equivalent, e.g. pause/resume/repath, sel fill).
 * Navigation becomes an operation that can be polled and cancelled, and finishes with a real outcome
 * taken from Baritone's path events.
 */
final class BaritoneBindings {
    private BaritoneBindings() {}

    private static final BaritoneAdapter A = new ReflectiveBaritone(
            BaritoneBindings.class.getClassLoader(), c -> new BlockPos(c[0], c[1], c[2]));

    private static final String LOCK = "player.movement";
    /** Bumped for every new navigation task, so a preempted task doesn't cancel its successor's path. */
    private static final AtomicInteger GENERATION = new AtomicInteger();
    /** Verbs accepted by baritone.command. Excludes `set` (use set_setting) and `sel`/`build`/file commands (typed actions below). */
    private static final Set<String> ALLOWED_VERBS = Set.of(
            "goto", "follow", "explore", "mine", "farm", "thisway", "tunnel", "come", "surface", "axis",
            "cancel", "stop", "pause", "resume", "repath");
    private static final String NAME_RE = "[A-Za-z0-9_]{1,16}";

    static void register(ApiContext ctx) {
        ctx.integration("baritone", A::installed);
        ctx.events.declare("baritone.path_event");
        A.addEventListener(name -> {
            JsonObject d = new JsonObject();
            d.addProperty("event", name);
            ctx.events.publish("baritone.path_event", d);
        });

        ctx.resources.register("baritone.status", new ResourceDef("baritone.status",
                "Baritone availability, current goal/process, and recent path events", Availability.LOCAL, "read.world", q -> {
                    if (!A.installed()) return Unavailable.of("baritone", Reason.MOD_NOT_INSTALLED);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("installed", true);
                    m.put("pathing", A.isPathing());
                    m.put("goal", A.goal());
                    m.put("process", A.activeProcess());
                    m.put("recentEvents", A.eventsAfter(Math.max(0, A.eventSeq() - 10)));
                    return m;
                }));
        ctx.resources.register("baritone.path", new ResourceDef("baritone.path",
                "Current path nodes (capped at 1000)", Availability.LOCAL, "read.world", q -> {
                    if (!A.installed()) return Unavailable.of("baritone.path", Reason.MOD_NOT_INSTALLED);
                    List<int[]> nodes = A.pathNodes(1000);
                    return Map.of("nodeCount", nodes.size(), "nodes", nodes);
                }));
        ctx.resources.register("baritone.settings", new ResourceDef("baritone.settings",
                "All Baritone settings of the installed build. Params: offset, limit",
                Availability.LOCAL, "read.client", q -> {
                    if (!A.installed()) return Unavailable.of("baritone.settings", Reason.MOD_NOT_INSTALLED);
                    return Page.of(A.settings(), q, 200, 1000);
                }));

        process(ctx, "control.mods", "baritone.goto", "Pathfind to coordinates (y optional)", b -> b
                .param(ParamDef.required("x", "integer", "Block X"))
                .param(ParamDef.optional("y", "integer", "Block Y", null))
                .param(ParamDef.required("z", "integer", "Block Z")),
                p -> {
                    int x = p.integer("x"), z = p.integer("z");
                    if (p.has("y")) {
                        int y = p.integer("y");
                        return () -> typedOr(() -> A.gotoPos(x, y, z), "goto " + x + " " + y + " " + z);
                    }
                    return () -> typedOr(() -> A.gotoXZ(x, z), "goto " + x + " " + z);
                });
        process(ctx, "control.mods", "baritone.goto_block", "Pathfind to the nearest known block of a type", b -> b
                .param(ParamDef.required("block", "string", "Block id, e.g. minecraft:crafting_table")),
                p -> {
                    String id = Validation.resourceId(p.string("block"));
                    return () -> typedOr(() -> A.gotoBlock(id), "goto " + id);
                });
        process(ctx, "control.mods", "baritone.follow_player", "Follow a player by name", b -> b
                .param(ParamDef.required("player", "string", "Player name")),
                p -> {
                    String name = checked(p.string("player"), NAME_RE);
                    return () -> typedOr(() -> A.follow(e -> e instanceof Player pl
                            && pl.getGameProfile().getName().equalsIgnoreCase(name)), "follow player " + name);
                });
        process(ctx, "control.mods", "baritone.follow_entity", "Follow entities of a type", b -> b
                .param(ParamDef.required("entityType", "string", "Entity type id, e.g. minecraft:cow")),
                p -> {
                    String raw = Validation.resourceId(p.string("entityType"));
                    String id = raw.contains(":") ? raw : "minecraft:" + raw;
                    return () -> typedOr(() -> A.follow(e -> e instanceof Entity en
                            && BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).toString().equals(id)), "follow entity " + id);
                });
        process(ctx, "control.mods", "baritone.explore", "Explore, optionally around an origin (defaults to your position)", b -> b
                .param(ParamDef.optional("x", "integer", "Origin X", null))
                .param(ParamDef.optional("z", "integer", "Origin Z", null)),
                p -> {
                    if (p.has("x") != p.has("z")) throw ApiException.badRequest("Give both x and z, or neither");
                    LocalPlayer pl = Minecraft.getInstance().player;
                    int x = p.has("x") ? p.integer("x") : (int) Math.floor(pl.getX());
                    int z = p.has("z") ? p.integer("z") : (int) Math.floor(pl.getZ());
                    return () -> typedOr(() -> A.explore(x, z), "explore " + x + " " + z);
                });
        process(ctx, "control.mods", "baritone.mine", "Mine blocks of the given types", b -> b
                .param(ParamDef.required("blocks", "string", "Space-separated block ids")),
                p -> {
                    List<String> ids = new ArrayList<>();
                    for (String s : p.string("blocks").trim().split("\\s+")) ids.add(Validation.resourceId(s));
                    return () -> typedOr(() -> A.mine(ids), "mine " + String.join(" ", ids));
                });

        simple(ctx, "control.mods", "baritone.cancel", "Cancel the current Baritone task",
                () -> typedOr(A::cancel, "cancel"));
        simple(ctx, "control.mods", "baritone.pause", "Pause Baritone", () -> A.command("pause"));
        simple(ctx, "control.mods", "baritone.resume", "Resume Baritone", () -> A.command("resume"));
        simple(ctx, "control.mods", "baritone.repath", "Recalculate the current path", () -> A.command("repath"));

        registerBuildActions(ctx);

        ctx.actions.register("baritone.set_setting", ActionDef.builder("baritone.set_setting", "Change a Baritone setting")
                .param(ParamDef.required("name", "string", "Setting name"))
                .param(ParamDef.required("value", "string", "New value, parsed by Baritone"))
                .permission("control.mods").availability(Availability.LOCAL).lock(LOCK)
                .handler(p -> {
                    A.command("set " + checked(p.string("name"), "[A-Za-z0-9]+") + " " + checked(p.string("value"), "[A-Za-z0-9_.:,\\- ]{1,200}"));
                    return Map.of("set", p.string("name"));
                }));

        ctx.actions.register("baritone.command", ActionDef.builder("baritone.command",
                        "Escape hatch: run an allowlisted Baritone command (" + String.join(", ", new java.util.TreeSet<>(ALLOWED_VERBS))
                                + "). Prefer the typed baritone.* actions; this is fire-and-forget, not tracked as an operation")
                .param(ParamDef.required("command", "string", "Command without the # prefix"))
                .permission("control.mods").lock(LOCK)
                .handler(p -> {
                    String line = Validation.commandLine(p.string("command"), ALLOWED_VERBS);
                    A.command(line);
                    return Map.of("executed", line);
                }));
    }

    // ---- bulk block editing: select / fill / clear / replace / build (permission control.build) ----

    private static final List<String> SHAPES = List.of("solid", "walls", "shell", "sphere", "hollow_sphere");
    private static final Map<String, String> SHAPE_TO_SEL = Map.of(
            "solid", "set", "walls", "walls", "shell", "shell", "sphere", "sphere", "hollow_sphere", "hsphere");

    private static ActionDef.Builder withCorners(ActionDef.Builder b) {
        for (String c : new String[]{"x1", "y1", "z1", "x2", "y2", "z2"})
            b.param(ParamDef.required(c, "integer", "Box corner coordinate " + c));
        return b;
    }

    /** Validates the box (volume cap from config) and returns the two selection commands. */
    private static List<String> selectBox(Params p) {
        int x1 = p.integer("x1"), y1 = p.integer("y1"), z1 = p.integer("z1");
        int x2 = p.integer("x2"), y2 = p.integer("y2"), z2 = p.integer("z2");
        Validation.boxVolume(x1, y1, z1, x2, y2, z2, ClientConfig.MAX_EDIT_VOLUME.get());
        return List.of("sel pos1 " + x1 + " " + y1 + " " + z1, "sel pos2 " + x2 + " " + y2 + " " + z2);
    }

    private static List<String> selectThen(Params p, String selCommand) {
        List<String> l = new ArrayList<>(selectBox(p));
        l.add(selCommand);
        return l;
    }

    private static void runAll(List<String> commands) { for (String c : commands) A.command(c); }

    private static java.nio.file.Path schematicsDir() {
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("schematics");
    }

    private static void registerBuildActions(ApiContext ctx) {
        // No typed equivalent is used for fill/replace: Baritone's schematic composition classes are
        // too version-dependent to construct reliably by reflection, so these go through `sel`.
        process(ctx, "control.build", "baritone.fill",
                "Fill a box with a block (solid, walls, shell, sphere or hollow_sphere) using Baritone's selection tools",
                b -> withCorners(b.param(ParamDef.required("block", "string", "Block id, e.g. minecraft:stone"))
                        .param(new ParamDef("shape", "string", false, "Fill shape", "solid", SHAPES))),
                p -> {
                    List<String> cmds = selectThen(p, "sel " + SHAPE_TO_SEL.get(p.string("shape", "solid")) + " " + Validation.resourceId(p.string("block")));
                    return () -> runAll(cmds);
                });

        process(ctx, "control.build", "baritone.clear_area", "Break every block in a box",
                b -> withCorners(b),
                p -> {
                    List<String> cmds = selectThen(p, "sel cleararea");
                    int x1 = p.integer("x1"), y1 = p.integer("y1"), z1 = p.integer("z1");
                    int x2 = p.integer("x2"), y2 = p.integer("y2"), z2 = p.integer("z2");
                    return () -> typedOr(() -> A.clearArea(x1, y1, z1, x2, y2, z2), cmds.toArray(new String[0]));
                });

        process(ctx, "control.build", "baritone.replace", "Replace one block type with another inside a box",
                b -> withCorners(b.param(ParamDef.required("find", "string", "Block id to replace"))
                        .param(ParamDef.required("replace", "string", "Block id to place instead"))),
                p -> {
                    List<String> cmds = selectThen(p, "sel replace " + Validation.resourceId(p.string("find")) + " " + Validation.resourceId(p.string("replace")));
                    return () -> runAll(cmds);
                });

        simple(ctx, "control.build", "baritone.clear_selection", "Clear Baritone's current selection", () -> A.command("sel clear"));

        process(ctx, "control.build", "baritone.build",
                "Build a schematic from the game's schematics folder (bare file name; see resource baritone.schematics). "
                        + "Optional origin x,y,z (all three or none; defaults to your position)",
                b -> b.param(ParamDef.required("schematic", "string", "File name, e.g. house.schem"))
                        .param(ParamDef.optional("x", "integer", "Origin X", null))
                        .param(ParamDef.optional("y", "integer", "Origin Y", null))
                        .param(ParamDef.optional("z", "integer", "Origin Z", null)),
                p -> {
                    String name = Validation.schematicName(p.string("schematic"));
                    java.nio.file.Path dir = schematicsDir().normalize();
                    java.nio.file.Path file = dir.resolve(name).normalize();
                    if (!file.startsWith(dir) || !java.nio.file.Files.isRegularFile(file))
                        throw ApiException.notFound("No such schematic in the schematics folder: " + name);
                    int given = (p.has("x") ? 1 : 0) + (p.has("y") ? 1 : 0) + (p.has("z") ? 1 : 0);
                    if (given != 0 && given != 3) throw ApiException.badRequest("Give all of x, y, z or none");
                    BlockPos origin = given == 3 ? new BlockPos(p.integer("x"), p.integer("y"), p.integer("z"))
                                                 : Minecraft.getInstance().player.blockPosition();
                    return () -> typedOr(() -> A.buildSchematic(file.toFile(), origin.getX(), origin.getY(), origin.getZ()),
                            "build " + name + " " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
                });

        ctx.resources.register("baritone.schematics", new ResourceDef("baritone.schematics",
                "File names of schematics in the game's schematics folder (names only). Params: offset, limit",
                Availability.LOCAL, "read.client", q -> {
                    java.nio.file.Path dir = schematicsDir();
                    if (!java.nio.file.Files.isDirectory(dir)) return Page.of(List.<String>of(), q, 200, 1000);
                    try (var stream = java.nio.file.Files.list(dir)) {
                        List<String> names = stream.filter(java.nio.file.Files::isRegularFile)
                                .map(f -> f.getFileName().toString())
                                .filter(n -> { try { Validation.schematicName(n); return true; } catch (ApiException e) { return false; } })
                                .sorted().toList();
                        return Page.of(names, q, 200, 1000);
                    } catch (java.io.IOException e) {
                        throw new ApiException(500, "ACTION_FAILED", "Cannot list schematics folder");
                    }
                }));
    }

    // ---- helpers ----

    /** Runs the typed API call; if the installed Baritone lacks it (UNSUPPORTED), runs the fallback commands instead. */
    private static void typedOr(Runnable typed, String... fallbackCommands) {
        try {
            typed.run();
        } catch (ApiException e) {
            if (!"UNSUPPORTED".equals(e.code)) throw e;
            for (String c : fallbackCommands) A.command(c);
        }
    }

    private static String checked(String s, String regex) {
        if (!s.matches(regex)) throw ApiException.badRequest("Invalid value: " + s);
        return s;
    }

    private interface Customizer { ActionDef.Builder apply(ActionDef.Builder b); }
    /** Validates parameters and returns the code to run; anything that can fail validation must happen here. */
    private interface Starter { Runnable prepare(Params p); }

    /** Starts a Baritone process and tracks it as a cancellable operation that ends with Baritone's real outcome. */
    private static void process(ApiContext ctx, String permission, String name, String desc, Customizer c, Starter starter) {
        ActionDef.Builder b = ActionDef.builder(name, desc).permission(permission).lock(LOCK);
        ctx.actions.register(name, c.apply(b).handler(p -> {
            requirePlayer();
            Runnable go = starter.prepare(p);
            long since = A.eventSeq();
            go.run();
            return new NavTask(since);
        }));
    }

    private static void simple(ApiContext ctx, String permission, String name, String desc, Runnable body) {
        ctx.actions.register(name, ActionDef.builder(name, desc).permission(permission).lock(LOCK).handler(p -> {
            body.run();
            return Map.of("executed", name);
        }));
    }

    private static void requirePlayer() {
        if (Minecraft.getInstance().player == null) throw ApiException.unavailable("Player is not connected to a world");
    }

    /**
     * Tracks a Baritone task. Pathing starts asynchronously, so we wait a few ticks before treating
     * "idle" as "finished", and use Baritone's path events to say how it ended: AT_GOAL, CALC_FAILED
     * (operation FAILED), CANCELED, or plain idle for processes without a goal (mine, explore, follow).
     */
    private static final class NavTask implements LongRunning {
        private static final int GRACE_TICKS = 10;
        private final long since;
        private final int generation = GENERATION.incrementAndGet();
        private int ticks;
        private boolean cancelled;

        NavTask(long since) { this.since = since; }

        public Object tick() {
            ticks++;
            List<String> ev = A.eventsAfter(since);
            if (ev.contains("AT_GOAL")) return finished("AT_GOAL", ev);
            if (ticks <= GRACE_TICKS || A.busy()) return null;
            if (ev.contains("CALC_FAILED") || ev.contains("NEXT_CALC_FAILED"))
                throw new IllegalStateException("Baritone could not find a path (CALC_FAILED)");
            if (ev.contains("CANCELED")) return finished("CANCELED", ev);
            return finished("IDLE", ev);
        }

        private Object finished(String reason, List<String> events) {
            LocalPlayer p = Minecraft.getInstance().player;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("finished", true);
            r.put("reason", reason);
            r.put("position", p == null ? null : List.of(p.getX(), p.getY(), p.getZ()));
            r.put("pathEvents", events);
            return r;
        }

        public void cancel() {
            if (cancelled) return;
            cancelled = true;
            if (generation == GENERATION.get()) typedOr(A::cancel, "cancel");
        }

        public Object progress() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pathing", A.isPathing());
            m.put("goal", A.goal());
            m.put("process", A.activeProcess());
            m.put("pathEvents", A.eventsAfter(since));
            return m;
        }
    }
}

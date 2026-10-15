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
 * Actions start the Baritone process and return at once; progress is visible through baritone.status,
 * baritone.path and the `baritone.path_event` event stream (AT_GOAL, CALC_FAILED, CANCELED, ...), and
 * baritone.cancel stops it - the same shape as Baritone's own API.
 */
final class BaritoneBindings {
    private BaritoneBindings() {}

    private static final BaritoneAdapter A = new ReflectiveBaritone(
            BaritoneBindings.class.getClassLoader(), c -> new BlockPos(c[0], c[1], c[2]));

    /** Verbs accepted by baritone.command. Excludes `set` (use set_setting) and `sel`/`build`/file commands (typed actions below). */
    private static final Set<String> ALLOWED_VERBS = Set.of(
            "goto", "follow", "explore", "mine", "farm", "thisway", "tunnel", "come", "surface", "axis",
            "cancel", "stop", "pause", "resume", "repath");
    private static final String NAME_RE = "[A-Za-z0-9_]{1,16}";
    private static final String WAYPOINT_RE = "[A-Za-z0-9_\\-]{1,32}";

    static void register(ApiContext ctx) {
        ctx.integration("baritone", A::installed);
        ctx.events.declare("baritone.path_event");
        A.addEventListener(name -> {
            JsonObject d = new JsonObject();
            d.addProperty("event", name);
            ctx.events.publish("baritone.path_event", d);
        });

        ctx.resources.register("baritone.status", new ResourceDef("baritone.status",
                "Baritone availability, current goal/process, and the latest path event", Availability.LOCAL, q -> {
                    if (!A.installed()) return Unavailable.of("baritone", Reason.MOD_NOT_INSTALLED);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("installed", true);
                    m.put("pathing", A.isPathing());
                    m.put("goal", A.goal());
                    m.put("process", A.activeProcess());
                    m.put("lastPathEvent", A.lastPathEvent());
                    return m;
                }));
        ctx.resources.register("baritone.path", new ResourceDef("baritone.path",
                "Current path nodes (capped at 1000)", Availability.LOCAL, q -> {
                    if (!A.installed()) return Unavailable.of("baritone.path", Reason.MOD_NOT_INSTALLED);
                    List<int[]> nodes = A.pathNodes(1000);
                    return Map.of("nodeCount", nodes.size(), "nodes", nodes);
                }));
        ctx.resources.register("baritone.settings", new ResourceDef("baritone.settings",
                "All Baritone settings of the installed build. Params: offset, limit",
                Availability.LOCAL, q -> {
                    if (!A.installed()) return Unavailable.of("baritone.settings", Reason.MOD_NOT_INSTALLED);
                    return Page.of(A.settings(), q, 200, 1000);
                }));

        act(ctx, "baritone.goto", "Pathfind to coordinates (y optional)", b -> b
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
        act(ctx, "baritone.goto_block", "Pathfind to the nearest known block of a type", b -> b
                .param(ParamDef.required("block", "string", "Block id, e.g. minecraft:crafting_table")),
                p -> {
                    String id = Validation.resourceId(p.string("block"));
                    return () -> typedOr(() -> A.gotoBlock(id), "goto " + id);
                });
        act(ctx, "baritone.follow_player", "Follow a player by name", b -> b
                .param(ParamDef.required("player", "string", "Player name")),
                p -> {
                    String name = checked(p.string("player"), NAME_RE);
                    return () -> typedOr(() -> A.follow(e -> e instanceof Player pl
                            && pl.getGameProfile().getName().equalsIgnoreCase(name)), "follow player " + name);
                });
        act(ctx, "baritone.follow_entity", "Follow entities of a type", b -> b
                .param(ParamDef.required("entityType", "string", "Entity type id, e.g. minecraft:cow")),
                p -> {
                    String raw = Validation.resourceId(p.string("entityType"));
                    String id = raw.contains(":") ? raw : "minecraft:" + raw;
                    return () -> typedOr(() -> A.follow(e -> e instanceof Entity en
                            && BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).toString().equals(id)), "follow entity " + id);
                });
        act(ctx, "baritone.explore", "Explore, optionally around an origin (defaults to your position)", b -> b
                .param(ParamDef.optional("x", "integer", "Origin X", null))
                .param(ParamDef.optional("z", "integer", "Origin Z", null)),
                p -> {
                    if (p.has("x") != p.has("z")) throw ApiException.badRequest("Give both x and z, or neither");
                    LocalPlayer pl = Minecraft.getInstance().player;
                    int x = p.has("x") ? p.integer("x") : (int) Math.floor(pl.getX());
                    int z = p.has("z") ? p.integer("z") : (int) Math.floor(pl.getZ());
                    return () -> typedOr(() -> A.explore(x, z), "explore " + x + " " + z);
                });
        act(ctx, "baritone.mine", "Mine blocks of the given types (quantity 0 = until cancelled)", b -> b
                .param(ParamDef.required("blocks", "string", "Space-separated block ids"))
                .param(ParamDef.optional("quantity", "integer", "How many to collect; 0 = unlimited", 0)),
                p -> {
                    List<String> ids = new ArrayList<>();
                    for (String s : p.string("blocks").trim().split("\\s+")) ids.add(Validation.resourceId(s));
                    int q = inRange(p.integer("quantity", 0), 0, 100_000, "quantity");
                    return () -> typedOr(() -> A.mine(ids, q), "mine " + (q > 0 ? q + " " : "") + String.join(" ", ids));
                });
        act(ctx, "baritone.farm", "Harvest and replant nearby crops", b -> b
                .param(ParamDef.optional("range", "integer", "Farming radius; 0 = Baritone's default", 0)),
                p -> {
                    int range = inRange(p.integer("range", 0), 0, 256, "range");
                    return () -> typedOr(() -> A.farm(range), range > 0 ? "farm " + range : "farm");
                });
        act(ctx, "baritone.tunnel", "Dig a tunnel in the direction you face (height, width, depth: all three or none)", b -> b
                .param(ParamDef.optional("height", "integer", "Tunnel height", null))
                .param(ParamDef.optional("width", "integer", "Tunnel width", null))
                .param(ParamDef.optional("depth", "integer", "Tunnel depth", null)),
                p -> {
                    int given = (p.has("height") ? 1 : 0) + (p.has("width") ? 1 : 0) + (p.has("depth") ? 1 : 0);
                    if (given != 0 && given != 3) throw ApiException.badRequest("Give all of height, width, depth or none");
                    String cmd = given == 0 ? "tunnel" : "tunnel " + inRange(p.integer("height"), 1, 64, "height") + " "
                            + inRange(p.integer("width"), 1, 64, "width") + " " + inRange(p.integer("depth"), 1, 10_000, "depth");
                    return () -> A.command(cmd);
                });
        act(ctx, "baritone.thisway", "Walk N blocks in the direction you are facing", b -> b
                .param(ParamDef.required("distance", "integer", "Blocks to travel (1..10000)")),
                p -> {
                    int d = inRange(p.integer("distance"), 1, 10_000, "distance");
                    return () -> A.command("thisway " + d);
                });
        act(ctx, "baritone.axis", "Travel along the nearest axis or diagonal from the origin", b -> b,
                p -> () -> A.command("axis"));
        act(ctx, "baritone.surface", "Go up to the surface", b -> b, p -> () -> A.command("surface"));
        act(ctx, "baritone.blacklist",
                "Blacklist the closest block of the current mine/get-to-block target (use when Baritone is stuck on it)",
                () -> A.command("blacklist"));

        act(ctx, "baritone.waypoint_goto", "Pathfind to a saved Baritone waypoint", b -> b
                .param(ParamDef.required("name", "string", "Waypoint name")),
                p -> {
                    String name = checked(p.string("name"), WAYPOINT_RE);
                    return () -> A.command("wp goto " + name);
                });
        ctx.actions.register("baritone.waypoint_save", ActionDef.builder("baritone.waypoint_save", "Save your current position as a Baritone waypoint")
                .param(ParamDef.required("name", "string", "Waypoint name (letters, digits, _ and -)"))
                .handler(p -> {
                    requirePlayer();
                    String name = checked(p.string("name"), WAYPOINT_RE);
                    A.command("wp save user " + name);
                    return Map.of("saved", name);
                }));
        ctx.resources.register("baritone.waypoints", new ResourceDef("baritone.waypoints",
                "Baritone waypoints (name, tag, x, y, z). Params: offset, limit", Availability.LOCAL, q -> {
                    if (!A.installed()) return Unavailable.of("baritone.waypoints", Reason.MOD_NOT_INSTALLED);
                    return Page.of(A.waypoints(), q, 200, 1000);
                }));
        ctx.resources.register("baritone.find", new ResourceDef("baritone.find",
                "Known locations of a block type in Baritone's world cache, nearest regions to you first. Params: block (required), limit (default 100, max 1000)",
                Availability.LOCAL, q -> {
                    if (!A.installed()) return Unavailable.of("baritone.find", Reason.MOD_NOT_INSTALLED);
                    String id = Validation.resourceId(q.string("block"));
                    int limit = inRange(q.integer("limit", 100), 1, 1000, "limit");
                    LocalPlayer pl = requirePlayerNow();
                    List<int[]> found = A.locationsOf(id, limit, (int) Math.floor(pl.getX()), (int) Math.floor(pl.getZ()));
                    return Map.of("block", id, "count", found.size(), "locations", found);
                }));
        act(ctx, "baritone.set_goal_path", "Set a goal and start pathing to it (same as goto but via the goal/path commands)", b -> b
                .param(ParamDef.required("x", "integer", "Block X"))
                .param(ParamDef.required("y", "integer", "Block Y"))
                .param(ParamDef.required("z", "integer", "Block Z")),
                p -> {
                    int x = p.integer("x"), y = p.integer("y"), z = p.integer("z");
                    return () -> { A.command("goal " + x + " " + y + " " + z); A.command("path"); };
                });

        act(ctx, "baritone.cancel", "Cancel the current Baritone task",
                () -> typedOr(A::cancel, "cancel"));
        act(ctx, "baritone.pause", "Pause Baritone", () -> A.command("pause"));
        act(ctx, "baritone.resume", "Resume Baritone", () -> A.command("resume"));
        act(ctx, "baritone.repath", "Recalculate the current path", () -> A.command("repath"));

        registerBuildActions(ctx);

        ctx.actions.register("baritone.set_setting", ActionDef.builder("baritone.set_setting", "Change a Baritone setting")
                .param(ParamDef.required("name", "string", "Setting name"))
                .param(ParamDef.required("value", "string", "New value, parsed by Baritone"))
                .availability(Availability.LOCAL)
                .handler(p -> {
                    A.command("set " + checked(p.string("name"), "[A-Za-z0-9]+") + " " + checked(p.string("value"), "[A-Za-z0-9_.:,\\- ]{1,200}"));
                    return Map.of("set", p.string("name"));
                }));

        ctx.actions.register("baritone.command", ActionDef.builder("baritone.command",
                        "Escape hatch: run an allowlisted Baritone command (" + String.join(", ", new java.util.TreeSet<>(ALLOWED_VERBS))
                                + "). Prefer the typed baritone.* actions; fire-and-forget")
                .param(ParamDef.required("command", "string", "Command without the # prefix"))
                .handler(p -> {
                    String line = Validation.commandLine(p.string("command"), ALLOWED_VERBS);
                    A.command(line);
                    return Map.of("executed", line);
                }));
    }

    // ---- bulk block editing: select / fill / clear / replace / build (box size capped by config) ----

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
        act(ctx, "baritone.fill",
                "Fill a box with a block (solid, walls, shell, sphere or hollow_sphere) using Baritone's selection tools",
                b -> withCorners(b.param(ParamDef.required("block", "string", "Block id, e.g. minecraft:stone"))
                        .param(new ParamDef("shape", "string", false, "Fill shape", "solid", SHAPES))),
                p -> {
                    List<String> cmds = selectThen(p, "sel " + SHAPE_TO_SEL.get(p.string("shape", "solid")) + " " + Validation.resourceId(p.string("block")));
                    return () -> runAll(cmds);
                });

        act(ctx, "baritone.clear_area", "Break every block in a box",
                b -> withCorners(b),
                p -> {
                    List<String> cmds = selectThen(p, "sel cleararea");
                    int x1 = p.integer("x1"), y1 = p.integer("y1"), z1 = p.integer("z1");
                    int x2 = p.integer("x2"), y2 = p.integer("y2"), z2 = p.integer("z2");
                    return () -> typedOr(() -> A.clearArea(x1, y1, z1, x2, y2, z2), cmds.toArray(new String[0]));
                });

        act(ctx, "baritone.replace", "Replace one block type with another inside a box",
                b -> withCorners(b.param(ParamDef.required("find", "string", "Block id to replace"))
                        .param(ParamDef.required("replace", "string", "Block id to place instead"))),
                p -> {
                    List<String> cmds = selectThen(p, "sel replace " + Validation.resourceId(p.string("find")) + " " + Validation.resourceId(p.string("replace")));
                    return () -> runAll(cmds);
                });

        act(ctx, "baritone.copy_area", "Copy a box (relative to you) to Baritone's clipboard",
                b -> withCorners(b), p -> { List<String> cmds = selectThen(p, "sel copy"); return () -> runAll(cmds); });
        act(ctx, "baritone.paste", "Build the copied clipboard at your current position (move first)",
                b -> b, p -> () -> A.command("sel paste"));

        act(ctx, "baritone.clear_selection", "Clear Baritone's current selection", () -> A.command("sel clear"));

        act(ctx, "baritone.build",
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
                Availability.LOCAL, q -> {
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

    private static int inRange(int v, int min, int max, String name) {
        if (v < min || v > max) throw ApiException.badRequest("'" + name + "' must be between " + min + " and " + max);
        return v;
    }

    private static LocalPlayer requirePlayerNow() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw ApiException.unavailable("Player is not connected to a world");
        return p;
    }

    private static String checked(String s, String regex) {
        if (!s.matches(regex)) throw ApiException.badRequest("Invalid value: " + s);
        return s;
    }

    private interface Customizer { ActionDef.Builder apply(ActionDef.Builder b); }
    /** Validates parameters and returns the code to run; anything that can fail validation must happen here. */
    private interface Starter { Runnable prepare(Params p); }

    /** Validates parameters, then starts the Baritone action and returns immediately. Observe it via baritone.status / path events. */
    private static void act(ApiContext ctx, String name, String desc, Customizer c, Starter starter) {
        ctx.actions.register(name, c.apply(ActionDef.builder(name, desc)).handler(p -> {
            requirePlayer();
            Runnable go = starter.prepare(p); // fully validated before anything is executed
            go.run();
            return Map.of("started", name);
        }));
    }

    /** An action with no parameters. */
    private static void act(ApiContext ctx, String name, String desc, Runnable body) {
        ctx.actions.register(name, ActionDef.builder(name, desc).handler(p -> {
            body.run();
            return Map.of("done", name);
        }));
    }

    private static void requirePlayer() {
        if (Minecraft.getInstance().player == null) throw ApiException.unavailable("Player is not connected to a world");
    }
}

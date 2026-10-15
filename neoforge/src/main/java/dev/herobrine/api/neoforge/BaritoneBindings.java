package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Baritone endpoints. Always registered so they stay discoverable; they report the integration as
 * unavailable when Baritone is absent. Navigation goes through Baritone's own command manager and
 * becomes an operation that can be polled and cancelled.
 */
final class BaritoneBindings {
    private BaritoneBindings() {}

    private static final String LOCK = "player.movement";
    /** Bumped for every new navigation task, so a preempted task doesn't cancel its successor's path. */
    private static final java.util.concurrent.atomic.AtomicInteger GENERATION = new java.util.concurrent.atomic.AtomicInteger();
    /** Verbs accepted by baritone.command. Excludes `set` (use set_setting) and `sel`/`build`/file commands (typed actions below). */
    private static final java.util.Set<String> ALLOWED_VERBS = java.util.Set.of(
            "goto", "follow", "explore", "mine", "farm", "thisway", "tunnel", "come", "surface", "axis",
            "cancel", "stop", "pause", "resume", "repath");
    private static final String NAME_RE = "[A-Za-z0-9_]{1,16}";

    static void register(ApiContext ctx) {
        ctx.integration("baritone", BaritoneBridge::installed);

        ctx.resources.register("baritone.status", new ResourceDef("baritone.status",
                "Baritone availability, current goal/process and progress", Availability.LOCAL, "read.world", q -> {
                    if (!BaritoneBridge.installed()) return Unavailable.of("baritone", Reason.MOD_NOT_INSTALLED);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("installed", true);
                    m.put("pathing", BaritoneBridge.isPathing());
                    m.put("goal", BaritoneBridge.goal());
                    m.put("process", BaritoneBridge.activeProcess());
                    return m;
                }));
        ctx.resources.register("baritone.path", new ResourceDef("baritone.path",
                "Current path nodes (capped at 1000)", Availability.LOCAL, "read.world", q -> {
                    if (!BaritoneBridge.installed()) return Unavailable.of("baritone.path", Reason.MOD_NOT_INSTALLED);
                    List<int[]> nodes = BaritoneBridge.pathNodes(1000);
                    return Map.of("nodeCount", nodes.size(), "nodes", nodes);
                }));
        ctx.resources.register("baritone.settings", new ResourceDef("baritone.settings",
                "All Baritone settings of the installed build. Params: offset, limit",
                Availability.LOCAL, "read.client", q -> {
                    if (!BaritoneBridge.installed()) return Unavailable.of("baritone.settings", Reason.MOD_NOT_INSTALLED);
                    return Page.of(BaritoneBridge.settings(), q, 200, 1000);
                }));

        nav(ctx, "baritone.goto", "Pathfind to coordinates (y optional)", b -> b
                .param(ParamDef.required("x", "integer", "Block X"))
                .param(ParamDef.optional("y", "integer", "Block Y", null))
                .param(ParamDef.required("z", "integer", "Block Z")),
                p -> p.has("y") ? "goto " + p.integer("x") + " " + p.integer("y") + " " + p.integer("z")
                                : "goto " + p.integer("x") + " " + p.integer("z"));
        nav(ctx, "baritone.goto_block", "Pathfind to the nearest known block of a type", b -> b
                .param(ParamDef.required("block", "string", "Block id, e.g. minecraft:crafting_table")),
                p -> "goto " + Validation.resourceId(p.string("block")));
        nav(ctx, "baritone.follow_player", "Follow a player by name", b -> b
                .param(ParamDef.required("player", "string", "Player name")),
                p -> "follow player " + checked(p.string("player"), NAME_RE));
        nav(ctx, "baritone.follow_entity", "Follow entities of a type", b -> b
                .param(ParamDef.required("entityType", "string", "Entity type id, e.g. minecraft:cow")),
                p -> "follow entity " + Validation.resourceId(p.string("entityType")));
        nav(ctx, "baritone.explore", "Explore, optionally around an origin", b -> b
                .param(ParamDef.optional("x", "integer", "Origin X", null))
                .param(ParamDef.optional("z", "integer", "Origin Z", null)),
                p -> p.has("x") && p.has("z") ? "explore " + p.integer("x") + " " + p.integer("z") : "explore");
        nav(ctx, "baritone.mine", "Mine blocks of the given types", b -> b
                .param(ParamDef.required("blocks", "string", "Space-separated block ids")),
                p -> {
                    StringBuilder sb = new StringBuilder("mine");
                    for (String s : p.string("blocks").trim().split("\\s+")) sb.append(' ').append(Validation.resourceId(s));
                    return sb.toString();
                });

        simple(ctx, "baritone.cancel", "Cancel the current Baritone task", "cancel");
        simple(ctx, "baritone.pause", "Pause Baritone", "pause");
        simple(ctx, "baritone.resume", "Resume Baritone", "resume");
        simple(ctx, "baritone.repath", "Recalculate the current path", "repath");

        registerBuildActions(ctx);

        ctx.actions.register("baritone.set_setting", ActionDef.builder("baritone.set_setting", "Change a Baritone setting")
                .param(ParamDef.required("name", "string", "Setting name"))
                .param(ParamDef.required("value", "string", "New value, parsed by Baritone"))
                .permission("control.mods").availability(Availability.LOCAL).lock(LOCK)
                .handler(p -> {
                    BaritoneBridge.command("set " + checked(p.string("name"), "[A-Za-z0-9]+") + " " + checked(p.string("value"), "[A-Za-z0-9_.:,\\- ]{1,200}"));
                    return Map.of("set", p.string("name"));
                }));

        ctx.actions.register("baritone.command", ActionDef.builder("baritone.command",
                        "Run an allowlisted Baritone command (" + String.join(", ", new java.util.TreeSet<>(ALLOWED_VERBS))
                                + "); fire-and-forget, not tracked as an operation")
                .param(ParamDef.required("command", "string", "Command without the # prefix"))
                .permission("control.mods").lock(LOCK)
                .handler(p -> {
                    String line = Validation.commandLine(p.string("command"), ALLOWED_VERBS);
                    BaritoneBridge.command(line);
                    return Map.of("executed", line);
                }));
    }

    // ---- bulk block editing: select / fill / clear / replace / build (permission control.build) ----

    private static final List<String> SHAPES = List.of("solid", "walls", "shell", "sphere", "hollow_sphere");
    private static final java.util.Map<String, String> SHAPE_TO_SEL = java.util.Map.of(
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
        List<String> l = new java.util.ArrayList<>(selectBox(p));
        l.add(selCommand);
        return l;
    }

    private static java.nio.file.Path schematicsDir() {
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("schematics");
    }

    private static void registerBuildActions(ApiContext ctx) {
        process(ctx, "control.build", "baritone.fill",
                "Fill a box with a block (solid, walls, shell, sphere or hollow_sphere) using Baritone's selection tools",
                b -> withCorners(b.param(ParamDef.required("block", "string", "Block id, e.g. minecraft:stone"))
                        .param(new ParamDef("shape", "string", false, "Fill shape", "solid", SHAPES))),
                p -> selectThen(p, "sel " + SHAPE_TO_SEL.get(p.string("shape", "solid")) + " " + Validation.resourceId(p.string("block"))));

        process(ctx, "control.build", "baritone.clear_area", "Break every block in a box",
                b -> withCorners(b), p -> selectThen(p, "sel cleararea"));

        process(ctx, "control.build", "baritone.replace", "Replace one block type with another inside a box",
                b -> withCorners(b.param(ParamDef.required("find", "string", "Block id to replace"))
                        .param(ParamDef.required("replace", "string", "Block id to place instead"))),
                p -> selectThen(p, "sel replace " + Validation.resourceId(p.string("find")) + " " + Validation.resourceId(p.string("replace"))));

        simple(ctx, "control.build", "baritone.clear_selection", "Clear Baritone's current selection", "sel clear");

        process(ctx, "control.build", "baritone.build",
                "Build a schematic from the game's schematics folder (bare file name; see resource baritone.schematics). "
                        + "Optional origin x,y,z (all three or none)",
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
                    return List.of(given == 3 ? "build " + name + " " + p.integer("x") + " " + p.integer("y") + " " + p.integer("z")
                                              : "build " + name);
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

    private static String checked(String s, String regex) {
        if (!s.matches(regex)) throw ApiException.badRequest("Invalid value: " + s);
        return s;
    }

    private interface Customizer { ActionDef.Builder apply(ActionDef.Builder b); }
    private interface CommandBuilder { String build(Params p); }
    private interface CommandsBuilder { List<String> build(Params p); }

    private static void nav(ApiContext ctx, String name, String desc, Customizer c, CommandBuilder cmd) {
        process(ctx, "control.mods", name, desc, c, p -> List.of(cmd.build(p)));
    }

    /** Runs one or more Baritone commands and tracks the resulting Baritone process as a cancellable operation. */
    private static void process(ApiContext ctx, String permission, String name, String desc, Customizer c, CommandsBuilder cmds) {
        ActionDef.Builder b = ActionDef.builder(name, desc).permission(permission).lock(LOCK);
        ctx.actions.register(name, c.apply(b).handler(p -> {
            requirePlayer();
            List<String> lines = cmds.build(p); // fully validated before anything is executed
            for (String line : lines) BaritoneBridge.command(line);
            return new NavTask();
        }));
    }

    private static void simple(ApiContext ctx, String name, String desc, String command) {
        simple(ctx, "control.mods", name, desc, command);
    }

    private static void simple(ApiContext ctx, String permission, String name, String desc, String command) {
        ctx.actions.register(name, ActionDef.builder(name, desc).permission(permission).lock(LOCK).handler(p -> {
            BaritoneBridge.command(command);
            return Map.of("executed", command);
        }));
    }

    private static void requirePlayer() {
        if (Minecraft.getInstance().player == null) throw ApiException.unavailable("Player is not connected to a world");
    }

    /**
     * Tracks a Baritone task. Pathing starts asynchronously, so we wait a few ticks before treating
     * "idle" as "finished". Baritone does not expose a failure reason; failures appear only in chat.
     */
    private static final class NavTask implements LongRunning {
        private static final int GRACE_TICKS = 10;
        private int ticks;
        private boolean cancelled;
        private final int generation = GENERATION.incrementAndGet();

        public Object tick() {
            if (++ticks <= GRACE_TICKS) return null;
            if (BaritoneBridge.busy()) return null;
            LocalPlayer p = Minecraft.getInstance().player;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("finished", true);
            r.put("position", p == null ? null : List.of(p.getX(), p.getY(), p.getZ()));
            r.put("note", "Baritone went idle; success/failure is not reported by its API");
            return r;
        }

        public void cancel() {
            if (cancelled) return;
            cancelled = true;
            if (generation == GENERATION.get()) BaritoneBridge.command("cancel");
        }

        public Object progress() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pathing", BaritoneBridge.isPathing());
            m.put("goal", BaritoneBridge.goal());
            m.put("process", BaritoneBridge.activeProcess());
            return m;
        }
    }
}

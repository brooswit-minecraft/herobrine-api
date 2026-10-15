package dev.herobrine.api.neoforge;

import com.google.gson.JsonObject;
import dev.herobrine.api.core.*;
import dev.herobrine.api.core.baritone.BaritoneAdapter;
import dev.herobrine.api.core.baritone.BaritoneAdapter.Box;
import dev.herobrine.api.core.baritone.BaritoneAdapter.Shape;
import dev.herobrine.api.core.baritone.ReflectiveBaritone;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Baritone endpoints. They stay registered (and discoverable) when Baritone is absent and then report
 * the integration as unavailable. Everything goes through Baritone's typed Java API via
 * {@link BaritoneAdapter}; its command manager is never used. Actions start the Baritone process and
 * return at once: watch {@code baritone.status} / {@code baritone.path} or the {@code baritone.path_event}
 * event stream (AT_GOAL, CALC_FAILED, CANCELED, ...), and stop it with {@code baritone.cancel} - the same
 * shape as Baritone's own API. If the installed build lacks a call the action fails with 501 UNSUPPORTED.
 */
final class BaritoneBindings {
    private BaritoneBindings() {}

    private static final BaritoneAdapter A = new ReflectiveBaritone(
            BaritoneBindings.class.getClassLoader(), c -> new BlockPos(c[0], c[1], c[2]));

    private static final String PLAYER_NAME = "[A-Za-z0-9_]{1,16}";
    private static final String WAYPOINT_NAME = "[A-Za-z0-9_\\-]{1,32}";
    private static final String SETTING_NAME = "[A-Za-z0-9]+";
    private static final List<String> SHAPES = List.of("solid", "walls", "shell", "sphere", "hollow_sphere");

    static void register(ApiContext ctx) {
        ctx.integration("baritone", A::installed);
        ctx.events.declare("baritone.path_event");
        A.addEventListener(name -> {
            JsonObject d = new JsonObject();
            d.addProperty("event", name);
            ctx.events.publish("baritone.path_event", d);
        });

        registerResources(ctx);
        registerNavigation(ctx);
        registerBuilding(ctx);
        registerSettingsAndWaypoints(ctx);
    }

    // ---- resources ----

    private static void registerResources(ApiContext ctx) {
        resource(ctx, "baritone.status", "Baritone availability, current goal/process, and the latest path event", q -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("installed", true);
            m.put("pathing", A.isPathing());
            m.put("goal", A.goal());
            m.put("process", A.activeProcess());
            m.put("lastPathEvent", A.lastPathEvent());
            return m;
        });
        resource(ctx, "baritone.path", "Current path nodes (capped at 1000)", q -> {
            List<int[]> nodes = A.pathNodes(1000);
            return Map.of("nodeCount", nodes.size(), "nodes", nodes);
        });
        resource(ctx, "baritone.settings", "All Baritone settings of the installed build. Params: offset, limit",
                q -> Page.of(A.settings(), q, 200, 1000));
        resource(ctx, "baritone.waypoints", "Baritone waypoints (name, tag, x, y, z). Params: offset, limit",
                q -> Page.of(A.waypoints(), q, 200, 1000));
        resource(ctx, "baritone.find",
                "Known locations of a block type in Baritone's world cache, nearest regions to you first. Params: block (required), limit (default 100, max 1000)",
                q -> {
                    String id = Validation.resourceId(q.string("block"));
                    int limit = inRange(q.integer("limit", 100), 1, 1000, "limit");
                    LocalPlayer pl = requirePlayer();
                    List<int[]> found = A.locationsOf(id, limit, (int) Math.floor(pl.getX()), (int) Math.floor(pl.getZ()));
                    return Map.of("block", id, "count", found.size(), "locations", found);
                });
        // Not a Baritone resource: lists files in the game's schematics folder, names only.
        ctx.resources.register("baritone.schematics", new ResourceDef("baritone.schematics",
                "File names of schematics in the game's schematics folder (names only). Params: offset, limit",
                Availability.LOCAL, q -> Page.of(schematicNames(), q, 200, 1000)));
    }

    /** Registers a resource that reports MOD_NOT_INSTALLED when Baritone is absent. */
    private static void resource(ApiContext ctx, String id, String desc, Function<Params, Object> body) {
        ctx.resources.register(id, new ResourceDef(id, desc, Availability.LOCAL, q ->
                A.installed() ? body.apply(q) : Unavailable.of(id, Reason.MOD_NOT_INSTALLED)));
    }

    // ---- navigation ----

    private static void registerNavigation(ApiContext ctx) {
        action(ctx, "baritone.goto", "Pathfind to coordinates (y optional)", b -> b
                .param(ParamDef.required("x", "integer", "Block X"))
                .param(ParamDef.optional("y", "integer", "Block Y", null))
                .param(ParamDef.required("z", "integer", "Block Z")),
                p -> {
                    int x = p.integer("x"), z = p.integer("z");
                    if (!p.has("y")) return () -> A.gotoXZ(x, z);
                    int y = p.integer("y");
                    return () -> A.gotoPos(x, y, z);
                });
        action(ctx, "baritone.goto_block", "Pathfind to the nearest known block of a type", b -> b
                .param(ParamDef.required("block", "string", "Block id, e.g. minecraft:crafting_table")),
                p -> {
                    String id = Validation.resourceId(p.string("block"));
                    return () -> A.gotoBlock(id);
                });
        action(ctx, "baritone.thisway", "Walk N blocks in the direction you are facing", b -> b
                .param(ParamDef.required("distance", "integer", "Blocks to travel (1..10000)")),
                p -> {
                    int d = inRange(p.integer("distance"), 1, 10_000, "distance");
                    LocalPlayer pl = requirePlayer();
                    double yaw = Math.toRadians(pl.getYRot());
                    int x = (int) Math.floor(pl.getX() - Math.sin(yaw) * d);
                    int z = (int) Math.floor(pl.getZ() + Math.cos(yaw) * d);
                    return () -> A.gotoXZ(x, z);
                });
        action(ctx, "baritone.follow_player", "Follow a player by name", b -> b
                .param(ParamDef.required("player", "string", "Player name")),
                p -> {
                    String name = matching(p.string("player"), PLAYER_NAME, "player name");
                    return () -> A.follow(e -> e instanceof Player pl && pl.getGameProfile().getName().equalsIgnoreCase(name));
                });
        action(ctx, "baritone.follow_entity", "Follow entities of a type", b -> b
                .param(ParamDef.required("entityType", "string", "Entity type id, e.g. minecraft:cow")),
                p -> {
                    String raw = Validation.resourceId(p.string("entityType"));
                    String id = raw.contains(":") ? raw : "minecraft:" + raw;
                    return () -> A.follow(e -> e instanceof Entity en
                            && BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).toString().equals(id));
                });
        action(ctx, "baritone.explore", "Explore, optionally around an origin (defaults to your position)", b -> b
                .param(ParamDef.optional("x", "integer", "Origin X", null))
                .param(ParamDef.optional("z", "integer", "Origin Z", null)),
                p -> {
                    if (p.has("x") != p.has("z")) throw ApiException.badRequest("Give both x and z, or neither");
                    LocalPlayer pl = requirePlayer();
                    int x = p.has("x") ? p.integer("x") : (int) Math.floor(pl.getX());
                    int z = p.has("z") ? p.integer("z") : (int) Math.floor(pl.getZ());
                    return () -> A.explore(x, z);
                });
        action(ctx, "baritone.mine", "Mine blocks of the given types (quantity 0 = until cancelled)", b -> b
                .param(ParamDef.required("blocks", "string", "Space-separated block ids"))
                .param(ParamDef.optional("quantity", "integer", "How many to collect; 0 = unlimited", 0)),
                p -> {
                    List<String> ids = new ArrayList<>();
                    for (String s : p.string("blocks").trim().split("\\s+")) ids.add(Validation.resourceId(s));
                    int quantity = inRange(p.integer("quantity", 0), 0, 100_000, "quantity");
                    return () -> A.mine(ids, quantity);
                });
        action(ctx, "baritone.farm", "Harvest and replant nearby crops", b -> b
                .param(ParamDef.optional("range", "integer", "Farming radius; 0 = Baritone's default", 0)),
                p -> {
                    int range = inRange(p.integer("range", 0), 0, 256, "range");
                    return () -> A.farm(range);
                });
        action(ctx, "baritone.cancel", "Cancel whatever Baritone is doing", b -> b, p -> A::cancel);
    }

    // ---- building (box sizes are capped by the maxEditVolume config) ----

    private static void registerBuilding(ApiContext ctx) {
        action(ctx, "baritone.fill", "Fill a box with a block (solid, walls, shell, sphere or hollow_sphere)", b -> corners(b
                .param(ParamDef.required("block", "string", "Block id, e.g. minecraft:stone"))
                .param(new ParamDef("shape", "string", false, "Fill shape", "solid", SHAPES))),
                p -> {
                    Box box = box(p);
                    String block = Validation.resourceId(p.string("block"));
                    Shape shape = Shape.valueOf(p.string("shape", "solid").toUpperCase(java.util.Locale.ROOT));
                    return () -> A.fill(shape, block, box);
                });
        action(ctx, "baritone.clear_area", "Break every block in a box", BaritoneBindings::corners,
                p -> {
                    Box box = box(p);
                    return () -> A.clearArea(box);
                });
        action(ctx, "baritone.replace", "Replace one block type with another inside a box", b -> corners(b
                .param(ParamDef.required("find", "string", "Block id to replace"))
                .param(ParamDef.required("replace", "string", "Block id to place instead"))),
                p -> {
                    Box box = box(p);
                    String find = Validation.resourceId(p.string("find"));
                    String replace = Validation.resourceId(p.string("replace"));
                    return () -> A.replace(find, replace, box);
                });
        action(ctx, "baritone.build",
                "Build a schematic from the game's schematics folder (bare file name; see resource baritone.schematics). "
                        + "Optional origin x,y,z (all three or none; defaults to your position)",
                b -> b.param(ParamDef.required("schematic", "string", "File name, e.g. house.schem"))
                        .param(ParamDef.optional("x", "integer", "Origin X", null))
                        .param(ParamDef.optional("y", "integer", "Origin Y", null))
                        .param(ParamDef.optional("z", "integer", "Origin Z", null)),
                p -> {
                    String name = Validation.schematicName(p.string("schematic"));
                    Path dir = schematicsDir().normalize();
                    Path file = dir.resolve(name).normalize();
                    if (!file.startsWith(dir) || !Files.isRegularFile(file))
                        throw ApiException.notFound("No such schematic in the schematics folder: " + name);
                    int given = (p.has("x") ? 1 : 0) + (p.has("y") ? 1 : 0) + (p.has("z") ? 1 : 0);
                    if (given != 0 && given != 3) throw ApiException.badRequest("Give all of x, y, z or none");
                    BlockPos origin = given == 3 ? new BlockPos(p.integer("x"), p.integer("y"), p.integer("z"))
                                                 : requirePlayer().blockPosition();
                    return () -> A.buildSchematic(file.toFile(), origin.getX(), origin.getY(), origin.getZ());
                });
    }

    private static ActionDef.Builder corners(ActionDef.Builder b) {
        for (String c : new String[]{"x1", "y1", "z1", "x2", "y2", "z2"})
            b.param(ParamDef.required(c, "integer", "Box corner coordinate " + c));
        return b;
    }

    /** Reads the six corner parameters and enforces the configured volume cap. */
    private static Box box(Params p) {
        Box box = new Box(p.integer("x1"), p.integer("y1"), p.integer("z1"), p.integer("x2"), p.integer("y2"), p.integer("z2"));
        Validation.boxVolume(box.x1(), box.y1(), box.z1(), box.x2(), box.y2(), box.z2(), ClientConfig.MAX_EDIT_VOLUME.get());
        return box;
    }

    // ---- settings and waypoints ----

    private static void registerSettingsAndWaypoints(ApiContext ctx) {
        action(ctx, "baritone.set_setting", "Change a Baritone setting (boolean, integer, long, float, double or string)", b -> b
                .param(ParamDef.required("name", "string", "Setting name"))
                .param(ParamDef.required("value", "string", "New value")),
                p -> {
                    String name = matching(p.string("name"), SETTING_NAME, "setting name");
                    String value = Validation.settingValue(p.string("value"));
                    return () -> A.setSetting(name, value);
                });
        action(ctx, "baritone.waypoint_save", "Save your current position as a Baritone waypoint", b -> b
                .param(ParamDef.required("name", "string", "Waypoint name (letters, digits, _ and -)")),
                p -> {
                    String name = matching(p.string("name"), WAYPOINT_NAME, "waypoint name");
                    BlockPos pos = requirePlayer().blockPosition();
                    return () -> A.saveWaypoint(name, pos.getX(), pos.getY(), pos.getZ());
                });
        action(ctx, "baritone.waypoint_goto", "Pathfind to a saved Baritone waypoint", b -> b
                .param(ParamDef.required("name", "string", "Waypoint name")),
                p -> {
                    String name = matching(p.string("name"), WAYPOINT_NAME, "waypoint name");
                    return () -> {
                        for (Map<String, Object> w : A.waypoints()) {
                            if (name.equals(w.get("name")))
                                { A.gotoPos((Integer) w.get("x"), (Integer) w.get("y"), (Integer) w.get("z")); return; }
                        }
                        throw ApiException.notFound("No Baritone waypoint named " + name);
                    };
                });
    }

    // ---- helpers ----

    private interface Customizer { ActionDef.Builder apply(ActionDef.Builder b); }
    /** Validates parameters and returns the code to run; everything that can fail validation must happen here. */
    private interface Starter { Runnable prepare(Params p); }

    /** Registers an action that validates, starts the Baritone call, and returns immediately. */
    private static void action(ApiContext ctx, String name, String desc, Customizer c, Starter starter) {
        ctx.actions.register(name, c.apply(ActionDef.builder(name, desc)).handler(p -> {
            requirePlayer();
            Runnable go = starter.prepare(p);
            go.run();
            return Map.of("started", name);
        }));
    }

    private static LocalPlayer requirePlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw ApiException.unavailable("Player is not connected to a world");
        return p;
    }

    private static String matching(String s, String regex, String what) {
        if (!s.matches(regex)) throw ApiException.badRequest("Invalid " + what + ": " + s);
        return s;
    }

    private static int inRange(int v, int min, int max, String name) {
        if (v < min || v > max) throw ApiException.badRequest("'" + name + "' must be between " + min + " and " + max);
        return v;
    }

    private static Path schematicsDir() { return FMLPaths.GAMEDIR.get().resolve("schematics"); }

    private static List<String> schematicNames() {
        Path dir = schematicsDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(f -> f.getFileName().toString())
                    .filter(n -> { try { Validation.schematicName(n); return true; } catch (ApiException e) { return false; } })
                    .sorted().toList();
        } catch (IOException e) {
            throw new ApiException(500, "ACTION_FAILED", "Cannot list schematics folder");
        }
    }
}

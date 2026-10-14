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
    private static final String ID_RE = "[a-z0-9_.\\-]+(:[a-z0-9_./\\-]+)?";
    private static final String NAME_RE = "[A-Za-z0-9_]{1,16}";

    static void register(ApiContext ctx) {
        ctx.integration("baritone", BaritoneBridge::installed);

        ctx.resources.register("baritone.status", new ResourceDef("baritone.status",
                "Baritone availability, current goal/process and progress", Availability.LOCAL, "read.client", q -> {
                    if (!BaritoneBridge.installed()) return Unavailable.of("baritone", Reason.MOD_NOT_INSTALLED);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("installed", true);
                    m.put("pathing", BaritoneBridge.isPathing());
                    m.put("goal", BaritoneBridge.goal());
                    m.put("process", BaritoneBridge.activeProcess());
                    return m;
                }));
        ctx.resources.register("baritone.path", new ResourceDef("baritone.path",
                "Current path nodes (capped at 1000)", Availability.LOCAL, "read.client", q -> {
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
                p -> "goto " + checked(p.string("block"), ID_RE));
        nav(ctx, "baritone.follow_player", "Follow a player by name", b -> b
                .param(ParamDef.required("player", "string", "Player name")),
                p -> "follow player " + checked(p.string("player"), NAME_RE));
        nav(ctx, "baritone.follow_entity", "Follow entities of a type", b -> b
                .param(ParamDef.required("entityType", "string", "Entity type id, e.g. minecraft:cow")),
                p -> "follow entity " + checked(p.string("entityType"), ID_RE));
        nav(ctx, "baritone.explore", "Explore, optionally around an origin", b -> b
                .param(ParamDef.optional("x", "integer", "Origin X", null))
                .param(ParamDef.optional("z", "integer", "Origin Z", null)),
                p -> p.has("x") && p.has("z") ? "explore " + p.integer("x") + " " + p.integer("z") : "explore");
        nav(ctx, "baritone.mine", "Mine blocks of the given types", b -> b
                .param(ParamDef.required("blocks", "string", "Space-separated block ids")),
                p -> {
                    StringBuilder sb = new StringBuilder("mine");
                    for (String s : p.string("blocks").trim().split("\\s+")) sb.append(' ').append(checked(s, ID_RE));
                    return sb.toString();
                });

        simple(ctx, "baritone.cancel", "Cancel the current Baritone task", "cancel");
        simple(ctx, "baritone.pause", "Pause Baritone", "pause");
        simple(ctx, "baritone.resume", "Resume Baritone", "resume");
        simple(ctx, "baritone.repath", "Recalculate the current path", "repath");

        ctx.actions.register("baritone.set_setting", ActionDef.builder("baritone.set_setting", "Change a Baritone setting")
                .param(ParamDef.required("name", "string", "Setting name"))
                .param(ParamDef.required("value", "string", "New value, parsed by Baritone"))
                .permission("control.mods").availability(Availability.LOCAL)
                .handler(p -> {
                    BaritoneBridge.command("set " + checked(p.string("name"), "[A-Za-z0-9]+") + " " + checked(p.string("value"), "[A-Za-z0-9_.:,\\- ]{1,200}"));
                    return Map.of("set", p.string("name"));
                }));

        ctx.actions.register("baritone.command", ActionDef.builder("baritone.command",
                        "Run any Baritone command (see its #help); fire-and-forget, not tracked as an operation")
                .param(ParamDef.required("command", "string", "Command without the # prefix"))
                .permission("control.mods").lock(LOCK)
                .handler(p -> {
                    BaritoneBridge.command(p.string("command"));
                    return Map.of("executed", p.string("command"));
                }));
    }

    private static String checked(String s, String regex) {
        if (!s.matches(regex)) throw ApiException.badRequest("Invalid value: " + s);
        return s;
    }

    private interface Customizer { ActionDef.Builder apply(ActionDef.Builder b); }
    private interface CommandBuilder { String build(Params p); }

    private static void nav(ApiContext ctx, String name, String desc, Customizer c, CommandBuilder cmd) {
        ActionDef.Builder b = ActionDef.builder(name, desc).permission("control.mods").lock(LOCK);
        ctx.actions.register(name, c.apply(b).handler(p -> {
            requirePlayer();
            String line = cmd.build(p);
            BaritoneBridge.command(line);
            return new NavTask();
        }));
    }

    private static void simple(ApiContext ctx, String name, String desc, String command) {
        ctx.actions.register(name, ActionDef.builder(name, desc).permission("control.mods").handler(p -> {
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

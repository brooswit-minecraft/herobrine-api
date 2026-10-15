package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Entities and players the client currently knows about (what the server has sent it to render/track). */
final class EntityBindings {
    private EntityBindings() {}

    static void register(ApiContext ctx) {
        read(ctx, "entities",
                "Entities the client currently tracks, nearest first, excluding you. Params: radius (blocks, default 32, max 128), "
                        + "type (e.g. minecraft:zombie), limit (default 100, max 500)",
                q -> {
                    ClientLevel level = Game.level();
                    LocalPlayer self = Game.player();
                    int radius = Game.inRange(q.integer("radius", 32), 1, 128, "radius");
                    int limit = Game.inRange(q.integer("limit", 100), 1, 500, "limit");
                    String type = q.has("type") ? Validation.resourceId(q.string("type")) : null;
                    double max = (double) radius * radius;
                    List<Entity> found = new ArrayList<>();
                    for (Entity e : level.entitiesForRendering()) {
                        if (e == self || e.distanceToSqr(self) > max) continue;
                        if (type != null && !entityType(e).equals(type)) continue;
                        found.add(e);
                    }
                    found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(self)));
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Entity e : found.subList(0, Math.min(limit, found.size()))) out.add(summary(e, self));
                    return Map.of("count", found.size(), "returned", out.size(), "entities", out);
                });

        read(ctx, "entity",
                "One entity in detail: summary plus bounding box, tags, team, active effects and equipment. Params: id (runtime entity id) or uuid",
                q -> {
                    ClientLevel level = Game.level();
                    Entity e = null;
                    if (q.has("id")) e = level.getEntity(q.integer("id"));
                    else if (q.has("uuid")) {
                        String uuid = q.string("uuid");
                        for (Entity c : level.entitiesForRendering()) if (c.getUUID().toString().equals(uuid)) { e = c; break; }
                    } else throw ApiException.badRequest("Give id or uuid");
                    if (e == null) throw ApiException.notFound("No such entity is currently tracked by the client");
                    Map<String, Object> m = summary(e, Game.player());
                    AABB b = e.getBoundingBox();
                    m.put("boundingBox", List.of(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
                    m.put("eyeHeight", e.getEyeHeight());
                    m.put("tags", new ArrayList<>(e.getTags()));
                    m.put("team", e.getTeam() == null ? null : e.getTeam().getName());
                    if (e instanceof LivingEntity living) {
                        List<Map<String, Object>> effects = new ArrayList<>();
                        for (MobEffectInstance eff : living.getActiveEffects()) {
                            Map<String, Object> em = new LinkedHashMap<>();
                            em.put("effect", eff.getEffect().unwrapKey().map(k -> k.location().toString()).orElse(null));
                            em.put("amplifier", eff.getAmplifier());
                            em.put("duration", eff.getDuration());
                            effects.add(em);
                        }
                        Map<String, Object> equipment = new LinkedHashMap<>();
                        for (EquipmentSlot slot : EquipmentSlot.values()) {
                            Map<String, Object> stack = InventoryBindings.describe(living.getItemBySlot(slot), level.registryAccess());
                            if (stack != null) equipment.put(slot.getName(), stack);
                        }
                        m.put("effects", effects);
                        m.put("equipment", equipment);
                    }
                    return m;
                });

        read(ctx, "players",
                "The server's player list (tab list) with latency and game mode, plus position for players the client is tracking. "
                        + "Other players' inventories and stats are never sent to clients.",
                q -> {
                    var conn = Minecraft.getInstance().getConnection();
                    if (conn == null) throw ApiException.unavailable("Not connected");
                    ClientLevel level = Game.level();
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (PlayerInfo info : conn.getOnlinePlayers()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("uuid", info.getProfile().getId().toString());
                        m.put("name", info.getProfile().getName());
                        m.put("latency", info.getLatency());
                        m.put("gameMode", info.getGameMode().getName());
                        m.put("displayName", info.getTabListDisplayName() == null ? null : info.getTabListDisplayName().getString());
                        Entity tracked = level.getPlayerByUUID(info.getProfile().getId());
                        m.put("entityId", tracked == null ? null : tracked.getId());
                        m.put("position", tracked == null ? null : List.of(tracked.getX(), tracked.getY(), tracked.getZ()));
                        out.add(m);
                    }
                    return Map.of("count", out.size(), "players", out);
                });
    }

    private static String entityType(Entity e) { return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(); }

    private static Map<String, Object> summary(Entity e, Entity from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("uuid", e.getUUID().toString());
        m.put("type", entityType(e));
        m.put("name", e.getName().getString());
        m.put("customName", e.hasCustomName() ? e.getCustomName().getString() : null);
        m.put("position", List.of(e.getX(), e.getY(), e.getZ()));
        m.put("yaw", e.getYRot());
        m.put("pitch", e.getXRot());
        Vec3 v = e.getDeltaMovement();
        m.put("velocity", List.of(v.x, v.y, v.z));
        m.put("distance", Math.sqrt(e.distanceToSqr(from)));
        m.put("onGround", e.onGround());
        m.put("pose", e.getPose().name());
        m.put("onFire", e.isOnFire());
        m.put("invisible", e.isInvisible());
        m.put("vehicle", e.getVehicle() == null ? null : e.getVehicle().getId());
        List<Integer> passengers = new ArrayList<>();
        for (Entity p : e.getPassengers()) passengers.add(p.getId());
        m.put("passengers", passengers);
        if (e instanceof LivingEntity living) {
            m.put("health", living.getHealth());
            m.put("maxHealth", living.getMaxHealth());
        }
        return m;
    }

    private static void read(ApiContext ctx, String id, String desc, Function<Params, Object> body) {
        ctx.resources.register(id, new ResourceDef(id, desc, Availability.SERVER_SYNCED, body));
    }
}

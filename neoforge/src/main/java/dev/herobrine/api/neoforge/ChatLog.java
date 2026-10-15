package dev.herobrine.api.neoforge;

import com.google.gson.JsonObject;
import dev.herobrine.api.core.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

/**
 * Chat history. Minecraft keeps its chat lines private, so this records every message the client
 * receives (player chat, system messages, action bar) in a bounded buffer and also publishes each one
 * as a {@code chat.message} event. All access happens on the client thread.
 */
@EventBusSubscriber(modid = HerobrineApiMod.MOD_ID, value = Dist.CLIENT)
final class ChatLog {
    private ChatLog() {}

    private static final int MAX = 500;
    private static final ArrayDeque<Map<String, Object>> BUFFER = new ArrayDeque<>();
    private static long seq;
    private static volatile ApiContext ctx;

    static void register(ApiContext c) {
        ctx = c;
        c.events.declare("chat.message");
        c.resources.register("chat.history", new ResourceDef("chat.history",
                "Chat messages received since the mod loaded (last 500). Params: after (return only seq greater than this, default 0), limit (default 100, max 500)",
                Availability.SERVER_SYNCED, q -> {
                    long after = (long) q.number("after", 0);
                    int limit = Game.inRange(q.integer("limit", 100), 1, MAX, "limit");
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Map<String, Object> m : BUFFER) {
                        if ((Long) m.get("seq") > after) out.add(m);
                        if (out.size() >= limit) break;
                    }
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("latest", seq);
                    r.put("oldestKept", BUFFER.isEmpty() ? null : BUFFER.peekFirst().get("seq"));
                    r.put("messages", out);
                    return r;
                }));
    }

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        ApiContext c = ctx;
        if (c == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", ++seq);
        m.put("time", System.currentTimeMillis());
        m.put("tick", Minecraft.getInstance().level == null ? -1 : Minecraft.getInstance().level.getGameTime());
        m.put("text", event.getMessage().getString());
        m.put("sender", event.getSender() == null ? null : event.getSender().toString());
        boolean system = event instanceof ClientChatReceivedEvent.System;
        m.put("system", system);
        m.put("overlay", event instanceof ClientChatReceivedEvent.System s && s.isOverlay());
        BUFFER.addLast(m);
        while (BUFFER.size() > MAX) BUFFER.removeFirst();

        JsonObject d = new JsonObject();
        d.addProperty("seq", (Long) m.get("seq"));
        d.addProperty("text", (String) m.get("text"));
        d.addProperty("sender", (String) m.get("sender"));
        d.addProperty("system", system);
        c.events.publish("chat.message", d);
    }
}

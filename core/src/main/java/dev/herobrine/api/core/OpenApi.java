package dev.herobrine.api.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Builds the OpenAPI document and per-action schemas from the live registries. */
public final class OpenApi {
    private OpenApi() {}

    public static JsonObject paramSchema(ParamDef p) {
        JsonObject o = new JsonObject();
        o.addProperty("type", p.type());
        o.addProperty("description", p.description());
        if (p.defaultValue() != null) o.add("default", Json.GSON.toJsonTree(p.defaultValue()));
        if (p.enumValues() != null) o.add("enum", Json.GSON.toJsonTree(p.enumValues()));
        return o;
    }

    public static JsonObject actionSchema(ActionDef a) {
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        for (ParamDef p : a.params()) {
            props.add(p.name(), paramSchema(p));
            if (p.required()) required.add(p.name());
        }
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", props);
        s.add("required", required);
        s.addProperty("additionalProperties", false);
        return s;
    }

    public static JsonObject document(ApiContext ctx) {
        JsonObject doc = new JsonObject();
        doc.addProperty("openapi", "3.0.3");
        JsonObject info = new JsonObject();
        info.addProperty("title", "Minecraft Universal API");
        info.addProperty("version", "0.1.0");
        doc.add("info", info);

        JsonObject paths = new JsonObject();
        for (ResourceDef r : ctx.resources.all()) {
            JsonObject get = new JsonObject();
            get.addProperty("summary", r.description());
            get.addProperty("x-availability", r.availability().name());
            JsonObject responses = new JsonObject();
            responses.add("200", described("Resource value"));
            get.add("responses", responses);
            JsonObject item = new JsonObject();
            item.add("get", get);
            paths.add("/api/v1/resources/" + r.id(), item);
        }
        JsonObject schemas = new JsonObject();
        for (ActionDef a : ctx.actions.all()) {
            schemas.add("action:" + a.name(), actionSchema(a));
        }
        JsonObject exec = new JsonObject();
        JsonObject post = new JsonObject();
        post.addProperty("summary", "Execute an action by name (see /api/v1/actions)");
        JsonObject responses = new JsonObject();
        responses.add("200", described("Action result or operation handle"));
        post.add("responses", responses);
        exec.add("post", post);
        paths.add("/api/v1/actions/execute", exec);
        doc.add("paths", paths);

        JsonObject components = new JsonObject();
        components.add("schemas", schemas);
        doc.add("components", components);
        return doc;
    }

    private static JsonObject described(String d) {
        JsonObject o = new JsonObject();
        o.addProperty("description", d);
        return o;
    }
}

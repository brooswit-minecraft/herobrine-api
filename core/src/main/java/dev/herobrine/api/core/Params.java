package dev.herobrine.api.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Typed, validated view over a request's parameters. */
public final class Params {
    private final JsonObject json;

    public Params(JsonObject json) { this.json = json == null ? new JsonObject() : json; }

    public JsonObject raw() { return json; }
    public boolean has(String k) { return json.has(k) && !json.get(k).isJsonNull(); }

    private JsonElement req(String k) {
        if (!has(k)) throw ApiException.badRequest("Missing parameter: " + k);
        return json.get(k);
    }

    public String string(String k) { return req(k).getAsString(); }
    public String string(String k, String d) { return has(k) ? json.get(k).getAsString() : d; }
    public int integer(String k) { return num(k).intValue(); }
    public int integer(String k, int d) { return has(k) ? integer(k) : d; }
    public double number(String k) { return num(k).doubleValue(); }
    public double number(String k, double d) { return has(k) ? number(k) : d; }
    public boolean bool(String k, boolean d) { return has(k) ? json.get(k).getAsBoolean() : d; }

    private Number num(String k) {
        try { return req(k).getAsNumber(); }
        catch (RuntimeException e) { throw ApiException.badRequest("Parameter '" + k + "' must be a number"); }
    }
}

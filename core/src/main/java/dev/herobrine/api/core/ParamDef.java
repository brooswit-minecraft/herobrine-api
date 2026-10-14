package dev.herobrine.api.core;

import java.util.List;

/** Describes one action/query parameter; used for validation and schema generation. */
public record ParamDef(String name, String type, boolean required, String description, Object defaultValue, List<String> enumValues) {
    public static ParamDef required(String name, String type, String description) {
        return new ParamDef(name, type, true, description, null, null);
    }
    public static ParamDef optional(String name, String type, String description, Object def) {
        return new ParamDef(name, type, false, description, def, null);
    }
}

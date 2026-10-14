package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.ApiException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reflection facade over Baritone's published API (baritone.api.*). Reflection keeps Baritone an
 * optional, runtime-discovered dependency (spec 6.3) and lets us report whatever the installed
 * build actually supports. Unverified against a real Baritone build.
 */
final class BaritoneBridge {
    private BaritoneBridge() {}

    static boolean installed() {
        try { Class.forName("baritone.api.BaritoneAPI"); return true; }
        catch (ClassNotFoundException | LinkageError e) { return false; }
    }

    static Object baritone() {
        if (!installed()) throw ApiException.unavailable("Baritone is not installed");
        try {
            Object provider = call(Class.forName("baritone.api.BaritoneAPI"), "getProvider");
            Object b = call(provider, "getPrimaryBaritone");
            if (b == null) throw ApiException.unavailable("Baritone has no active instance (not in a world?)");
            return b;
        } catch (ClassNotFoundException e) {
            throw ApiException.unavailable("Baritone is not installed");
        }
    }

    /** Runs a Baritone chat command through its own command manager (no '#' prefix). */
    static void command(String line) {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) throw ApiException.badRequest("Invalid command");
        call(call(baritone(), "getCommandManager"), "execute", line);
    }

    static boolean isPathing() { return Boolean.TRUE.equals(call(call(baritone(), "getPathingBehavior"), "isPathing")); }

    static String goal() {
        Object g = call(call(baritone(), "getPathingBehavior"), "getGoal");
        return g == null ? null : g.toString();
    }

    /** Display name of the process currently controlling movement, or null. */
    static String activeProcess() {
        Object opt = call(call(baritone(), "getPathingControlManager"), "mostRecentInControl");
        if (opt instanceof Optional<?> o && o.isPresent()) return String.valueOf(call(o.get(), "displayName"));
        return null;
    }

    static boolean busy() { return isPathing() || activeProcess() != null; }

    /** Path node positions of the current path, capped. */
    static List<int[]> pathNodes(int cap) {
        Object opt = call(call(baritone(), "getPathingBehavior"), "getPath");
        List<int[]> out = new ArrayList<>();
        if (opt instanceof Optional<?> o && o.isPresent()) {
            Object positions = call(o.get(), "positions");
            if (positions instanceof List<?> l) {
                for (Object p : l) {
                    if (out.size() >= cap) break;
                    out.add(new int[]{(Integer) call(p, "getX"), (Integer) call(p, "getY"), (Integer) call(p, "getZ")});
                }
            }
        }
        return out;
    }

    /** Name/type/value/default for every setting the installed build defines. */
    static List<Map<String, Object>> settings() {
        Object settings = call(loadClass("baritone.api.BaritoneAPI"), "getSettings");
        try {
            Field all = settings.getClass().getField("allSettings");
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object s : (List<?>) all.get(settings)) {
                Map<String, Object> e = new java.util.LinkedHashMap<>();
                e.put("name", String.valueOf(call(s, "getName")));
                Object value = field(s, "value");
                e.put("type", value == null ? null : value.getClass().getSimpleName());
                e.put("value", value == null ? null : value.toString());
                Object def = field(s, "defaultValue");
                e.put("default", def == null ? null : def.toString());
                out.add(e);
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new ApiException(500, "ACTION_FAILED", "Cannot read Baritone settings: " + e);
        }
    }

    private static Object field(Object o, String name) {
        try {
            Field f = o.getClass().getField(name);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Class<?> loadClass(String n) {
        try { return Class.forName(n); } catch (ClassNotFoundException e) { throw ApiException.unavailable("Baritone is not installed"); }
    }

    /** Invokes a public method by name/arity on an instance, or statically when target is a Class. */
    static Object call(Object target, String name, Object... args) {
        boolean isStatic = target instanceof Class<?>;
        Class<?> c = isStatic ? (Class<?>) target : target.getClass();
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try {
                m.trySetAccessible();
                return m.invoke(isStatic ? null : target, args);
            } catch (InvocationTargetException e) {
                throw new ApiException(500, "ACTION_FAILED", "Baritone " + name + " failed: " + e.getCause());
            } catch (IllegalAccessException | IllegalArgumentException e) {
                continue; // try an overload
            }
        }
        throw new ApiException(501, "UNSUPPORTED", "Installed Baritone does not provide " + c.getSimpleName() + "." + name + "/" + args.length);
    }
}

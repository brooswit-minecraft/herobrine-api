package dev.herobrine.api.core.baritone;

import dev.herobrine.api.core.ApiException;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@link BaritoneAdapter} that talks to Baritone's API by reflection, so Baritone stays an optional
 * runtime dependency. Methods are looked up on the public interfaces of the objects Baritone returns
 * (not on implementation classes), which sidesteps module-access problems and keeps us tied to the
 * published API surface. Signatures are from memory of Baritone's API and are unverified.
 */
public final class ReflectiveBaritone implements BaritoneAdapter {
    private static final String API = "baritone.api.BaritoneAPI";
    private static final String GOAL_BLOCK = "baritone.api.pathing.goals.GoalBlock";
    private static final String GOAL_XZ = "baritone.api.pathing.goals.GoalXZ";
    private static final String BLOCK_META = "baritone.api.utils.BlockOptionalMeta";
    private static final String EVENT_LISTENER = "baritone.api.event.listener.IGameEventListener";
    private static final int EVENT_HISTORY = 64;

    private record Ev(long seq, String name) {}

    private final ClassLoader loader;
    private final Function<int[], Object> blockPos;
    private final AtomicLong seq = new AtomicLong();
    private final ConcurrentLinkedDeque<Ev> events = new ConcurrentLinkedDeque<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private volatile Object listeningOn;

    /** @param blockPos creates a Minecraft BlockPos from {x,y,z}; supplied by the platform module. */
    public ReflectiveBaritone(ClassLoader loader, Function<int[], Object> blockPos) {
        this.loader = loader;
        this.blockPos = blockPos;
    }

    // ---- availability ----

    @Override public boolean installed() {
        try { Class.forName(API, false, loader); return true; }
        catch (ClassNotFoundException | LinkageError e) { return false; }
    }

    private Object baritone() {
        if (!installed()) throw ApiException.unavailable("Baritone is not installed");
        Object provider = invokeStatic(load(API), "getProvider");
        Object b = invoke(provider, "getPrimaryBaritone");
        if (b == null) throw ApiException.unavailable("Baritone has no active instance (not in a world?)");
        ensureListener(b);
        return b;
    }

    // ---- processes ----

    @Override public void gotoPos(int x, int y, int z) {
        invoke(invoke(baritone(), "getCustomGoalProcess"), "setGoalAndPath", create(GOAL_BLOCK, x, y, z));
    }

    @Override public void gotoXZ(int x, int z) {
        invoke(invoke(baritone(), "getCustomGoalProcess"), "setGoalAndPath", create(GOAL_XZ, x, z));
    }

    @Override public void gotoBlock(String blockId) {
        invoke(invoke(baritone(), "getGetToBlockProcess"), "getToBlock", create(BLOCK_META, blockId));
    }

    @Override public void follow(Predicate<Object> entityMatcher) {
        invoke(invoke(baritone(), "getFollowProcess"), "follow", entityMatcher);
    }

    @Override public void explore(int x, int z) {
        invoke(invoke(baritone(), "getExploreProcess"), "explore", x, z);
    }

    @Override public void mine(List<String> blockIds) {
        invoke(invoke(baritone(), "getMineProcess"), "mineByName", 0, blockIds.toArray(new String[0]));
    }

    @Override public void cancel() {
        invoke(invoke(baritone(), "getPathingBehavior"), "cancelEverything");
    }

    // ---- building ----

    @Override public void buildSchematic(File file, int x, int y, int z) {
        Object ok = invoke(invoke(baritone(), "getBuilderProcess"), "build", file.getName(), file, blockPos.apply(new int[]{x, y, z}));
        if (Boolean.FALSE.equals(ok)) throw ApiException.badRequest("Baritone could not load schematic " + file.getName());
    }

    @Override public void clearArea(int x1, int y1, int z1, int x2, int y2, int z2) {
        invoke(invoke(baritone(), "getBuilderProcess"), "clearArea",
                blockPos.apply(new int[]{x1, y1, z1}), blockPos.apply(new int[]{x2, y2, z2}));
    }

    // ---- state ----

    @Override public boolean isPathing() {
        return Boolean.TRUE.equals(invoke(invoke(baritone(), "getPathingBehavior"), "isPathing"));
    }

    @Override public String goal() {
        Object g = invoke(invoke(baritone(), "getPathingBehavior"), "getGoal");
        return g == null ? null : g.toString();
    }

    @Override public String activeProcess() {
        Object opt = invoke(invoke(baritone(), "getPathingControlManager"), "mostRecentInControl");
        if (opt instanceof Optional<?> o && o.isPresent()) return String.valueOf(invoke(o.get(), "displayName"));
        return null;
    }

    @Override public List<int[]> pathNodes(int cap) {
        List<int[]> out = new ArrayList<>();
        Object opt = invoke(invoke(baritone(), "getPathingBehavior"), "getPath");
        if (opt instanceof Optional<?> o && o.isPresent() && invoke(o.get(), "positions") instanceof List<?> l) {
            for (Object p : l) {
                if (out.size() >= cap) break;
                out.add(new int[]{(Integer) invoke(p, "getX"), (Integer) invoke(p, "getY"), (Integer) invoke(p, "getZ")});
            }
        }
        return out;
    }

    @Override public List<Map<String, Object>> settings() {
        Object settings = invokeStatic(load(API), "getSettings");
        try {
            Object all = settings.getClass().getField("allSettings").get(settings);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object s : (List<?>) all) {
                Map<String, Object> e = new java.util.LinkedHashMap<>();
                e.put("name", String.valueOf(invoke(s, "getName")));
                Object value = field(s, "value");
                e.put("type", value == null ? null : value.getClass().getSimpleName());
                e.put("value", value == null ? null : value.toString());
                Object def = field(s, "defaultValue");
                e.put("default", def == null ? null : def.toString());
                out.add(e);
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new ApiException(501, "UNSUPPORTED", "Cannot read Baritone settings: " + e);
        }
    }

    @Override public void command(String line) {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) throw ApiException.badRequest("Invalid command");
        invoke(invoke(baritone(), "getCommandManager"), "execute", line);
    }

    // ---- events ----

    @Override public long eventSeq() { return seq.get(); }

    @Override public List<String> eventsAfter(long after) {
        List<String> out = new ArrayList<>();
        for (Ev e : events) if (e.seq() > after) out.add(e.name());
        return out;
    }

    @Override public void addEventListener(Consumer<String> l) { listeners.add(l); }

    private synchronized void ensureListener(Object baritone) {
        if (listeningOn == baritone) return;
        listeningOn = baritone; // don't retry on failure; events are best-effort
        try {
            Class<?> li = Class.forName(EVENT_LISTENER, false, loader);
            Object proxy = Proxy.newProxyInstance(li.getClassLoader(), new Class<?>[]{li}, (p, m, args) -> {
                if (m.getDeclaringClass() == Object.class) {
                    return switch (m.getName()) {
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        default -> "HerobrineApiPathListener";
                    };
                }
                if (m.getName().equals("onPathEvent") && args != null && args.length == 1) {
                    record(args[0] instanceof Enum<?> en ? en.name() : String.valueOf(args[0]));
                }
                return defaultFor(m.getReturnType());
            });
            invoke(invoke(baritone, "getGameEventHandler"), "registerEventListener", proxy);
        } catch (ClassNotFoundException | LinkageError | ApiException e) {
            // Path events unavailable in this build; navigation still works without them.
        }
    }

    private void record(String name) {
        events.addLast(new Ev(seq.incrementAndGet(), name));
        while (events.size() > EVENT_HISTORY) events.pollFirst();
        for (Consumer<String> l : listeners) {
            try { l.accept(name); } catch (RuntimeException ignored) {}
        }
    }

    private static Object defaultFor(Class<?> t) {
        if (!t.isPrimitive() || t == void.class) return null;
        if (t == boolean.class) return false;
        if (t == char.class) return '\0';
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        return 0d;
    }

    // ---- reflection helpers ----

    private Class<?> load(String name) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException | LinkageError e) { throw ApiException.unavailable("Baritone class not found: " + name); }
    }

    private Object create(String className, Object... args) {
        Class<?> c = load(className);
        for (Constructor<?> k : c.getConstructors()) {
            if (!paramsMatch(k.getParameterTypes(), args)) continue;
            try { return k.newInstance(args); }
            catch (InvocationTargetException e) { throw new ApiException(500, "ACTION_FAILED", className + " rejected arguments: " + e.getCause()); }
            catch (ReflectiveOperationException e) { throw new ApiException(500, "ACTION_FAILED", "Cannot construct " + className + ": " + e); }
        }
        throw new ApiException(501, "UNSUPPORTED", "Installed Baritone has no matching constructor for " + className);
    }

    private static Object invoke(Object target, String name, Object... args) {
        return run(findMethod(target.getClass(), name, args), target, name, args);
    }

    private static Object invokeStatic(Class<?> c, String name, Object... args) {
        return run(findMethod(c, name, args), null, name, args);
    }

    private static Object run(Method m, Object target, String name, Object[] args) {
        try { return m.invoke(target, args); }
        catch (InvocationTargetException e) { throw new ApiException(500, "ACTION_FAILED", "Baritone " + name + " failed: " + e.getCause()); }
        catch (IllegalAccessException e) { throw new ApiException(501, "UNSUPPORTED", "Baritone " + name + " is not accessible: " + e.getMessage()); }
    }

    /** Prefers methods declared on public interfaces (always accessible), then the class itself. */
    private static Method findMethod(Class<?> c, String name, Object[] args) {
        for (Class<?> i : allInterfaces(c))
            for (Method m : i.getMethods())
                if (m.getName().equals(name) && paramsMatch(m.getParameterTypes(), args)) return m;
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && paramsMatch(m.getParameterTypes(), args)) {
                m.trySetAccessible();
                return m;
            }
        }
        throw new ApiException(501, "UNSUPPORTED",
                "Installed Baritone does not provide " + c.getSimpleName() + "." + name + "/" + args.length);
    }

    private static Set<Class<?>> allInterfaces(Class<?> c) {
        Set<Class<?>> out = new LinkedHashSet<>();
        if (c.isInterface()) out.add(c);
        for (Class<?> k = c; k != null; k = k.getSuperclass()) collect(k, out);
        out.removeIf(i -> !java.lang.reflect.Modifier.isPublic(i.getModifiers()));
        return out;
    }

    private static void collect(Class<?> c, Set<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) { if (out.add(i)) collect(i, out); }
    }

    private static boolean paramsMatch(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = wrap(types[i]);
            if (args[i] == null ? types[i].isPrimitive() : !t.isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == boolean.class) return Boolean.class;
        if (t == long.class) return Long.class;
        if (t == double.class) return Double.class;
        if (t == float.class) return Float.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        return Character.class;
    }

    private static Object field(Object o, String name) {
        try { return o.getClass().getField(name).get(o); }
        catch (ReflectiveOperationException e) { return null; }
    }
}

package baritone.api;
public class Settings {
    public static class Setting {
        public Object value; public Object defaultValue; private final String name;
        public Setting(String name, Object value, Object defaultValue) { this.name = name; this.value = value; this.defaultValue = defaultValue; }
        public String getName() { return name; }
    }
    public static volatile Settings INSTANCE = new Settings();
    /** Test hook: restore every setting to its initial value. */
    public static void reset() { INSTANCE = new Settings(); }
    public final java.util.List<Setting> allSettings = java.util.List.of(
            new Setting("allowBreak", true, false), new Setting("maxFallHeight", 3, 3), new Setting("chatDebug", "x", "x"),
            new Setting("nullValue", null, null), new Setting("weird", new java.util.ArrayList<>(), null));
}

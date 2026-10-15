package baritone.api;
public class Settings {
    public static class Setting {
        public Object value = true;
        public Object defaultValue = false;
        public String getName() { return "allowBreak"; }
    }
    public final java.util.List<Setting> allSettings = java.util.List.of(new Setting());
}

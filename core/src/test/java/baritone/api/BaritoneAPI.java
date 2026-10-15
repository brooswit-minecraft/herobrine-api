package baritone.api;
/** Test double for Baritone's entry point; only the FQN and static methods matter. */
public final class BaritoneAPI {
    public static IBaritoneProvider getProvider() { return dev.herobrine.api.core.baritone.FakeBaritone.provider(); }
    public static Settings getSettings() { return new Settings(); }
}

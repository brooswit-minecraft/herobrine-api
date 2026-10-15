package dev.herobrine.api.core;


/** Input checks for text that ends up in chat or is handed to another mod. */
public final class Validation {
    public static final int MAX_CHAT = 256;

    private Validation() {}

    /**
     * Plain chat text: non-blank, at most 256 chars, no control characters and no '§' (vanilla
     * disconnects clients that send these). A leading '/' is rejected so commands go through a
     * separately permissioned action.
     */
    public static String chatText(String s) {
        if (s == null || s.isBlank()) throw ApiException.badRequest("Message must not be blank");
        if (s.length() > MAX_CHAT) throw ApiException.badRequest("Message exceeds " + MAX_CHAT + " characters");
        if (s.startsWith("/")) throw ApiException.badRequest("Messages starting with '/' are commands; use chat.command");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '§') throw ApiException.badRequest("Message contains a forbidden character");
        }
        return s;
    }

    /** A value for a typed setting: 1-200 characters, no control characters. */
    public static String settingValue(String s) {
        if (s == null || s.isEmpty() || s.length() > 200) throw ApiException.badRequest("Value must be 1-200 characters");
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) < 0x20 || s.charAt(i) == 0x7f) throw ApiException.badRequest("Value contains a control character");
        return s;
    }

    private static final java.util.regex.Pattern RESOURCE_ID = java.util.regex.Pattern.compile("[a-z0-9_.\\-]+(:[a-z0-9_./\\-]+)?");
    private static final java.util.regex.Pattern SCHEMATIC = java.util.regex.Pattern.compile("[A-Za-z0-9_\\-.]{1,100}\\.(schem|schematic|litematic|nbt)");
    private static final int WORLD_LIMIT = 30_000_000;

    /** A namespaced id such as "minecraft:stone" (namespace optional). */
    public static String resourceId(String s) {
        if (s == null || !RESOURCE_ID.matcher(s).matches()) throw ApiException.badRequest("Invalid id: " + s);
        return s;
    }

    /**
     * Bare schematic file name (no directories, no "..") with a known extension. The caller resolves it
     * inside one fixed directory, so a request can never name an arbitrary file.
     */
    public static String schematicName(String s) {
        if (s == null || s.contains("..") || !SCHEMATIC.matcher(s).matches())
            throw ApiException.badRequest("Schematic must be a plain file name ending in .schem, .schematic, .litematic or .nbt");
        return s;
    }

    /** Number of blocks in the inclusive box between two corners; rejects boxes larger than {@code maxVolume}. */
    public static long boxVolume(int x1, int y1, int z1, int x2, int y2, int z2, long maxVolume) {
        for (int v : new int[]{x1, y1, z1, x2, y2, z2})
            if (Math.abs((long) v) > WORLD_LIMIT) throw ApiException.badRequest("Coordinate out of range: " + v);
        long volume = (Math.abs((long) x1 - x2) + 1) * (Math.abs((long) y1 - y2) + 1) * (Math.abs((long) z1 - z2) + 1);
        if (volume > maxVolume) throw ApiException.badRequest("Selection of " + volume + " blocks exceeds the limit of " + maxVolume);
        return volume;
    }
}

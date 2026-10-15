package dev.herobrine.api.core;

import java.util.Set;

/** Input checks for text that ends up in chat or in another mod's command parser. */
public final class Validation {
    public static final int MAX_CHAT = 256;
    private static final java.util.regex.Pattern COMMAND_CHARS = java.util.regex.Pattern.compile("[A-Za-z0-9_:.,\\- ]{1,200}");

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

    /** A command line whose first word must be in {@code allowedVerbs} and whose characters are restricted. */
    public static String commandLine(String s, Set<String> allowedVerbs) {
        if (s == null) throw ApiException.badRequest("Missing command");
        String line = s.trim();
        if (!COMMAND_CHARS.matcher(line).matches()) throw ApiException.badRequest("Command contains forbidden characters or is too long");
        String verb = line.split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!allowedVerbs.contains(verb))
            throw ApiException.badRequest("Command '" + verb + "' is not allowed; allowed: " + new java.util.TreeSet<>(allowedVerbs));
        return line;
    }
}

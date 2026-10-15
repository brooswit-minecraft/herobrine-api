package dev.herobrine.api.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Scoped, revocable API tokens (spec 12.2/12.3). Secrets are held only as SHA-256 hashes. */
public final class TokenStore {
    public static final Set<String> ALL_PERMISSIONS = Set.of(
            "read.client", "read.world", "read.entities", "read.players", "read.inventory", "read.network", "read.gui",
            "control.input", "control.player", "control.inventory", "control.gui", "control.chat", "control.mods", "control.build",
            "control.server", "admin.api");

    public record Token(String id, String name, Set<String> permissions, long createdAtMillis) {
        /** Supports exact names, "*" and group wildcards such as "read.*". */
        public boolean allows(String permission) {
            if (permissions.contains("*") || permissions.contains(permission)) return true;
            int dot = permission.indexOf('.');
            return dot > 0 && permissions.contains(permission.substring(0, dot) + ".*");
        }
    }

    public record Created(Token token, String secret) {}

    private final Map<String, Token> byHash = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public Created create(String name, Set<String> permissions) {
        for (String p : permissions)
            if (!p.equals("*") && !ALL_PERMISSIONS.contains(p) && !p.matches("(read|control|admin)\\.\\*"))
                throw ApiException.badRequest("Unknown permission: " + p);
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String secret = HexFormat.of().formatHex(raw);
        Token t = new Token(HexFormat.of().formatHex(sha(secret)).substring(0, 8), name, Set.copyOf(permissions), System.currentTimeMillis());
        byHash.put(hash(secret), t);
        return new Created(t, secret);
    }

    public Optional<Token> authenticate(String presented) {
        return Optional.ofNullable(byHash.get(hash(presented)));
    }

    public Collection<Token> list() { return byHash.values(); }

    /** Takes effect immediately for subsequent requests. */
    public boolean revoke(String id) {
        return byHash.values().removeIf(t -> t.id().equals(id));
    }

    private static String hash(String s) { return HexFormat.of().formatHex(sha(s)); }

    private static byte[] sha(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

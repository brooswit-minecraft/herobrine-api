package dev.herobrine.api.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Exactly two secrets, kept in files so they survive restarts: a FULL token (read + control) and a
 * READ token (read only). Only SHA-256 hashes are held in memory. Rotate with {@link #regenerate()}.
 */
public final class Tokens {
    public enum Access { FULL, READ }

    private static final int MIN_SECRET_LENGTH = 32;

    private final Path fullFile, readFile;
    private final SecureRandom random = new SecureRandom();
    private volatile byte[] fullHash, readHash;

    private Tokens(Path fullFile, Path readFile) {
        this.fullFile = fullFile;
        this.readFile = readFile;
    }

    /** Reads existing secrets from the files, creating (or replacing malformed/duplicate) ones as needed. */
    public static Tokens loadOrCreate(Path fullFile, Path readFile) throws IOException {
        Tokens t = new Tokens(fullFile, readFile);
        String full = readSecret(fullFile);
        String read = readSecret(readFile);
        if (full == null) full = t.write(fullFile);
        if (read == null || read.equals(full)) read = t.write(readFile);
        t.fullHash = sha(full);
        t.readHash = sha(read);
        return t;
    }

    /** Replaces both secrets; the old ones stop working immediately. */
    public synchronized void regenerate() throws IOException {
        String full = write(fullFile);
        String read = write(readFile);
        fullHash = sha(full);
        readHash = sha(read);
    }

    public Optional<Access> authenticate(String presented) {
        if (presented == null) return Optional.empty();
        byte[] h = sha(presented);
        boolean full = MessageDigest.isEqual(h, fullHash);
        boolean read = MessageDigest.isEqual(h, readHash);
        return full ? Optional.of(Access.FULL) : read ? Optional.of(Access.READ) : Optional.empty();
    }

    public Path fullFile() { return fullFile; }
    public Path readFile() { return readFile; }

    private static String readSecret(Path f) throws IOException {
        if (!Files.isRegularFile(f)) return null;
        String s = Files.readString(f, StandardCharsets.UTF_8).trim();
        return s.length() >= MIN_SECRET_LENGTH ? s : null;
    }

    /** Writes a fresh secret to the file with owner-only permissions where the filesystem supports them. */
    private String write(Path f) throws IOException {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String secret = HexFormat.of().formatHex(raw);
        Files.deleteIfExists(f);
        try {
            Files.createFile(f, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(f);
        }
        Files.writeString(f, secret, StandardCharsets.UTF_8);
        return secret;
    }

    private static byte[] sha(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

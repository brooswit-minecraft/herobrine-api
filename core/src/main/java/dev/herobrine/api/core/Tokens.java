package dev.herobrine.api.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bearer secrets kept in files so they survive restarts. Two flavors:
 * <ul>
 *   <li>{@link #loadOrCreate}: a FULL token (read + control) and a READ token (read only) -- the
 *       client's established permission split.</li>
 *   <li>{@link #loadOrCreateSingle}: one simple operator-facing key, FULL access only -- what a
 *       dedicated server exposes. No new auth model: it is the same secret-in-a-file, SHA-256-hash,
 *       bearer-token pattern, just without a second (read-only) file.</li>
 * </ul>
 * Only SHA-256 hashes are held in memory; never the raw secret. On every {@link #authenticate}, the
 * backing file(s) are re-checked: a missing, deleted, empty, or whitespace-only value is regenerated
 * and persisted on the spot (no restart required), while an existing valid value is left untouched
 * (stable -- no spurious rewrite, no spurious rotation). A fresh value is only ever accepted after
 * it has been durably persisted (write-then-publish); a persistence failure fails closed. Concurrent
 * callers (threads within this JVM, independent {@code Tokens} instances within this JVM, or
 * separate processes racing on the same file) converge on exactly one persisted value via an
 * in-process monitor plus an OS file lock, and an atomic, non-clobbering rename.
 */
public final class Tokens {
    public enum Access { FULL, READ }

    /** A secret shorter than this (after trimming) is treated as missing and regenerated. */
    private static final int MIN_SECRET_LENGTH = 32;
    /** Entropy of a freshly generated secret: 24 random bytes -> 48 hex characters, 192 bits. */
    private static final int SECRET_ENTROPY_BYTES = 24;
    private static final String LOCK_SUFFIX = ".lock";
    private static final String TMP_PREFIX = ".tmp-";
    private static final String OWNER_ONLY = "rw-------";

    private final Path fullFile, readFile;
    private final boolean singleKey;
    private final SecureRandom random = new SecureRandom();
    private volatile byte[] fullHash, readHash;

    private Tokens(Path fullFile, Path readFile, boolean singleKey) {
        this.fullFile = fullFile;
        this.readFile = readFile;
        this.singleKey = singleKey;
    }

    /** FULL + READ tokens in two separate files (the client's permission split). */
    public static Tokens loadOrCreate(Path fullFile, Path readFile) throws IOException {
        Tokens t = new Tokens(fullFile, readFile, false);
        t.refresh();
        return t;
    }

    /** One simple FULL-only key in a single file (a dedicated server's operator-facing key). */
    public static Tokens loadOrCreateSingle(Path file) throws IOException {
        Tokens t = new Tokens(file, file, true);
        t.refresh();
        return t;
    }

    /**
     * Re-checks the backing file(s). Any value found missing, deleted, empty, or whitespace-only is
     * regenerated and persisted now, and only then becomes the value {@link #authenticate} accepts.
     * An existing valid value is left exactly as-is. Safe to call repeatedly and concurrently.
     */
    public synchronized void refresh() throws IOException {
        String full = ensureCurrent(fullFile);
        if (singleKey) {
            fullHash = sha(full);
            readHash = null;
            return;
        }
        String read = ensureCurrent(readFile);
        if (read.equals(full)) read = regenerate(readFile); // never let the read token equal the full one
        fullHash = sha(full);
        readHash = sha(read);
    }

    /** Replaces the secret(s) unconditionally; the old value(s) stop working immediately. */
    public synchronized void regenerate() throws IOException {
        String full = regenerate(fullFile);
        if (singleKey) {
            fullHash = sha(full);
            readHash = null;
            return;
        }
        String read = regenerate(readFile);
        if (read.equals(full)) read = regenerate(readFile);
        fullHash = sha(full);
        readHash = sha(read);
    }

    public Optional<Access> authenticate(String presented) {
        try {
            refresh();
        } catch (IOException e) {
            // Fail closed: never accept a key that was not confirmed persisted. Message is safe to
            // surface (it names a path, never a secret value).
            throw new TokensUnavailableException("Herobrine API token could not be read or regenerated: " + e.getMessage(), e);
        }
        if (presented == null) return Optional.empty();
        byte[] h = sha(presented);
        if (MessageDigest.isEqual(h, fullHash)) return Optional.of(Access.FULL);
        if (!singleKey && MessageDigest.isEqual(h, readHash)) return Optional.of(Access.READ);
        return Optional.empty();
    }

    public Path fullFile() { return fullFile; }
    public Path readFile() { return readFile; }

    /** Thrown when the backing secret could not be confirmed persisted; never carries the secret. */
    public static final class TokensUnavailableException extends RuntimeException {
        public TokensUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    // ---- disk access ----

    /** Returns the existing secret in {@code f}, or null if it is missing, deleted, empty, or blank. */
    private static String readSecret(Path f) throws IOException {
        if (!Files.isRegularFile(f)) return null;
        String s = Files.readString(f, StandardCharsets.UTF_8).trim();
        return s.length() >= MIN_SECRET_LENGTH ? s : null;
    }

    /** The existing valid secret in {@code f}, or a freshly generated and persisted one. */
    private String ensureCurrent(Path f) throws IOException {
        String existing = readSecret(f);
        if (existing != null) return existing;
        return withLock(f, () -> {
            // Re-check: another thread/process may have persisted a value while we waited for the lock.
            String raced = readSecret(f);
            if (raced != null) return raced;
            String secret = generateSecret();
            persist(f, secret);
            return secret;
        });
    }

    /** Unconditionally generates, persists, and returns a fresh secret for {@code f}. */
    private String regenerate(Path f) throws IOException {
        return withLock(f, () -> {
            String secret = generateSecret();
            persist(f, secret);
            return secret;
        });
    }

    private interface LockedWork {
        String run() throws IOException;
    }

    /**
     * Per-canonical-lock-path monitors, one per distinct lock file this JVM has touched. A JVM-wide
     * {@link FileLock} is advisory and keyed by the underlying file, but it is NOT a mutex: a second
     * attempt from the same JVM to lock a region it (or another channel in the same JVM) already
     * holds throws {@link java.nio.channels.OverlappingFileLockException} instead of blocking. So two
     * threads -- or two independent {@code Tokens} instances -- in this JVM racing on the same file
     * must be serialized here, in-process, before either reaches {@link FileChannel#lock()}.
     */
    private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS_LOCKS = new ConcurrentHashMap<>();

    /**
     * Runs {@code work} while holding both an in-process monitor (so same-JVM threads/instances
     * block and wait for each other rather than racing {@link FileChannel#lock()}) and an OS-level
     * lock on a sibling lock file (so other processes on the same machine also serialize).
     */
    private String withLock(Path f, LockedWork work) throws IOException {
        Path parent = f.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path lockPath = f.resolveSibling(f.getFileName().toString() + LOCK_SUFFIX);
        ReentrantLock inProcess = IN_PROCESS_LOCKS.computeIfAbsent(canonicalKey(lockPath), k -> new ReentrantLock());
        inProcess.lock();
        try {
            try (FileChannel ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = ch.lock()) {
                return work.run();
            }
        } finally {
            inProcess.unlock();
        }
    }

    /**
     * A stable identity for {@code lockPath} regardless of how the caller spelled it (relative vs.
     * absolute, symlinked parent, etc.), so the same underlying file always maps to the same
     * in-process monitor. The lock file itself may not exist yet, so this resolves the parent
     * directory (already created by the caller) to its real path and appends the file name, rather
     * than resolving the lock file's own real path.
     */
    private static Path canonicalKey(Path lockPath) throws IOException {
        Path abs = lockPath.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        Path parentReal = parent != null ? parent.toRealPath() : abs.getRoot().toRealPath();
        return parentReal.resolve(abs.getFileName());
    }

    private String generateSecret() {
        byte[] raw = new byte[SECRET_ENTROPY_BYTES];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    /**
     * Writes {@code secret} to {@code f} via write-temp-then-atomic-rename, so a reader never
     * observes a partially written value and a crash mid-write never leaves {@code f} corrupted.
     * Only returns once the rename has succeeded (persist-before-publish).
     */
    private void persist(Path f, String secret) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName().toString() + TMP_PREFIX + Long.toHexString(random.nextLong()));
        try {
            try {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
            } catch (UnsupportedOperationException e) {
                Files.createFile(tmp);
            }
            Files.writeString(tmp, secret, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw new IOException("could not persist token to " + f, e);
        }
    }

    private static byte[] sha(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

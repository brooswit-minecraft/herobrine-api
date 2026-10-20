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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bearer secrets, kept durable so they survive restarts. Two flavors:
 * <ul>
 *   <li>{@link #loadOrCreate}: a FULL token (read + control) and a READ token (read only) -- the
 *       client's established permission split, each in its own dedicated file.</li>
 *   <li>{@link #loadOrCreateSingle}: one simple operator-facing key, FULL access only. The
 *       {@link Path}-taking overload keeps it in a dedicated file; the {@link SecretAccessor}-taking
 *       overload lets a platform module store it anywhere else durable -- e.g. a field inside a
 *       larger config file, which is what the dedicated server uses.</li>
 * </ul>
 * Only SHA-256 hashes are held in memory; never the raw secret. On every {@link #authenticate}, the
 * backing store(s) are re-checked: a value the accessor reports missing/invalid is regenerated and
 * persisted on the spot (no restart required), while an existing valid value is left untouched
 * (stable -- no spurious rewrite, no spurious rotation). A fresh value is only ever accepted after
 * it has been durably persisted (write-then-publish); a persistence failure fails closed. Concurrent
 * callers (threads within this JVM, independent {@code Tokens} instances within this JVM, or
 * separate processes racing on the same backing store) converge on exactly one persisted value via
 * an in-process monitor plus an OS file lock keyed off {@link SecretAccessor#lockAnchor()}.
 */
public final class Tokens {
    public enum Access { FULL, READ }

    /** Entropy of a freshly generated secret: 24 random bytes -> 48 hex characters, 192 bits. */
    private static final int SECRET_ENTROPY_BYTES = 24;
    private static final String LOCK_SUFFIX = ".lock";

    private final SecretAccessor fullAccessor, readAccessor;
    private final boolean singleKey;
    private final SecureRandom random = new SecureRandom();
    private volatile byte[] fullHash, readHash;

    private Tokens(SecretAccessor fullAccessor, SecretAccessor readAccessor, boolean singleKey) {
        this.fullAccessor = fullAccessor;
        this.readAccessor = readAccessor;
        this.singleKey = singleKey;
    }

    /** FULL + READ tokens in two separate files (the client's permission split). */
    public static Tokens loadOrCreate(Path fullFile, Path readFile) throws IOException {
        Tokens t = new Tokens(new FileSecretAccessor(fullFile), new FileSecretAccessor(readFile), false);
        t.refresh();
        return t;
    }

    /** One simple FULL-only key in a single dedicated file. */
    public static Tokens loadOrCreateSingle(Path file) throws IOException {
        SecretAccessor a = new FileSecretAccessor(file);
        Tokens t = new Tokens(a, a, true);
        t.refresh();
        return t;
    }

    /**
     * One simple FULL-only key, backed by an arbitrary {@link SecretAccessor} instead of a
     * dedicated file -- e.g. a field inside a larger config file. Same
     * regenerate-on-access/stability/concurrency contract as the file-based flavors; no new auth
     * model, just a different place to durably persist the one secret.
     */
    public static Tokens loadOrCreateSingle(SecretAccessor accessor) throws IOException {
        Tokens t = new Tokens(accessor, accessor, true);
        t.refresh();
        return t;
    }

    /**
     * Re-checks the backing store(s). Any value the accessor reports missing/invalid is
     * regenerated and persisted now, and only then becomes the value {@link #authenticate}
     * accepts. An existing valid value is left exactly as-is. Safe to call repeatedly and
     * concurrently.
     */
    public synchronized void refresh() throws IOException {
        String full = ensureCurrent(fullAccessor);
        if (singleKey) {
            fullHash = sha(full);
            readHash = null;
            return;
        }
        String read = ensureCurrent(readAccessor);
        if (read.equals(full)) read = regenerate(readAccessor); // never let the read token equal the full one
        fullHash = sha(full);
        readHash = sha(read);
    }

    /** Replaces the secret(s) unconditionally; the old value(s) stop working immediately. */
    public synchronized void regenerate() throws IOException {
        String full = regenerate(fullAccessor);
        if (singleKey) {
            fullHash = sha(full);
            readHash = null;
            return;
        }
        String read = regenerate(readAccessor);
        if (read.equals(full)) read = regenerate(readAccessor);
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

    public Path fullFile() { return fullAccessor.lockAnchor(); }
    public Path readFile() { return readAccessor.lockAnchor(); }

    /** Thrown when the backing secret could not be confirmed persisted; never carries the secret. */
    public static final class TokensUnavailableException extends RuntimeException {
        public TokensUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Where a secret actually lives. The built-in {@link #loadOrCreate}/{@link #loadOrCreateSingle}
     * overloads use a private file-backed implementation; a platform module can implement this
     * directly to store the secret somewhere else durable (e.g. a field inside a larger config
     * file) while keeping every other guarantee {@link Tokens} provides.
     */
    public interface SecretAccessor {
        /**
         * The current secret, or {@code null} if missing, deleted, empty, whitespace-only, or
         * otherwise invalid. Called inside the lock when a regeneration decision is being made, so
         * implementations must read live state (never a value cached before this call).
         */
        String read() throws IOException;

        /**
         * Persists {@code secret} durably. Must only return once persistence has actually
         * succeeded (publish-after-persist); throw on any failure so the caller fails closed
         * instead of accepting an unpersisted value.
         */
        void write(String secret) throws IOException;

        /**
         * A stable path identifying this secret's backing storage. Used only to derive (a) the
         * in-process lock key and (b) an OS-level lock file (a {@code .lock} sibling created next
         * to it) -- never read or written to directly by {@link Tokens}. Two accessors that name
         * the same underlying storage must return equal-after-{@link Path#toRealPath} paths so
         * they serialize against each other correctly.
         */
        Path lockAnchor();
    }

    // ---- locking (shared by every accessor) ----

    /**
     * Per-canonical-lock-path monitors, one per distinct lock file this JVM has touched. A JVM-wide
     * {@link FileLock} is advisory and keyed by the underlying file, but it is NOT a mutex: a second
     * attempt from the same JVM to lock a region it (or another channel in the same JVM) already
     * holds throws {@link java.nio.channels.OverlappingFileLockException} instead of blocking. So two
     * threads -- or two independent {@code Tokens} instances -- in this JVM racing on the same
     * backing store must be serialized here, in-process, before either reaches {@link FileChannel#lock()}.
     */
    private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS_LOCKS = new ConcurrentHashMap<>();

    private interface LockedWork {
        String run() throws IOException;
    }

    /** The existing valid secret from {@code a}, or a freshly generated and persisted one. */
    private String ensureCurrent(SecretAccessor a) throws IOException {
        String existing = a.read();
        if (existing != null) return existing;
        return withLock(a, () -> {
            // Re-check: another thread/process may have persisted a value while we waited for the lock.
            String raced = a.read();
            if (raced != null) return raced;
            String secret = generateSecret();
            a.write(secret);
            return secret;
        });
    }

    /** Unconditionally generates, persists, and returns a fresh secret via {@code a}. */
    private String regenerate(SecretAccessor a) throws IOException {
        return withLock(a, () -> {
            String secret = generateSecret();
            a.write(secret);
            return secret;
        });
    }

    /**
     * Runs {@code work} while holding both an in-process monitor (so same-JVM threads/instances
     * block and wait for each other rather than racing {@link FileChannel#lock()}) and an OS-level
     * lock on a sibling lock file next to {@link SecretAccessor#lockAnchor()} (so other processes
     * on the same machine also serialize).
     */
    private String withLock(SecretAccessor a, LockedWork work) throws IOException {
        Path anchor = a.lockAnchor().toAbsolutePath();
        Path parent = anchor.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path lockPath = anchor.resolveSibling(anchor.getFileName().toString() + LOCK_SUFFIX);
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

    private static byte[] sha(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ---- built-in file-backed accessor ----

    /**
     * Default accessor: a dedicated secret file. A value shorter than {@link #MIN_SECRET_LENGTH}
     * (after trimming) is treated as missing. Written via write-temp-then-atomic-rename, so a
     * reader never observes a partially written value and a crash mid-write never leaves the file
     * corrupted.
     */
    private static final class FileSecretAccessor implements SecretAccessor {
        private static final int MIN_SECRET_LENGTH = 32;
        private static final String TMP_PREFIX = ".tmp-";
        private static final String OWNER_ONLY = "rw-------";

        private final Path file;

        FileSecretAccessor(Path file) { this.file = file; }

        @Override
        public String read() throws IOException {
            if (!Files.isRegularFile(file)) return null;
            String s = Files.readString(file, StandardCharsets.UTF_8).trim();
            return s.length() >= MIN_SECRET_LENGTH ? s : null;
        }

        @Override
        public void write(String secret) throws IOException {
            Path tmp = file.resolveSibling(file.getFileName().toString() + TMP_PREFIX + Long.toHexString(ThreadLocalRandom.current().nextLong()));
            try {
                try {
                    Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
                } catch (UnsupportedOperationException e) {
                    Files.createFile(tmp);
                }
                Files.writeString(tmp, secret, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw new IOException("could not persist token to " + file, e);
            }
        }

        @Override
        public Path lockAnchor() { return file; }
    }
}

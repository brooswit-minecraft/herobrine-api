package dev.herobrine.api.core.worldedit;

import dev.herobrine.api.core.ApiException;
import dev.herobrine.api.core.Validation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Confines request-supplied schematic names to one directory - WorldEdit's own schematics folder (see
 * {@link WorldEditAdapter#schematicsDirectory()}). A name must be a bare file name with a known extension
 * ({@link Validation#schematicName}, the same rule {@code baritone.build} uses), it is resolved against the
 * directory's real path, and anything that exists there but is not a plain regular file - a symbolic link
 * (which could point anywhere) or a directory - is refused, so a request can never read or write a file
 * outside the folder.
 */
public final class SchematicFiles {
    private final Supplier<Path> directory;

    public SchematicFiles(Supplier<Path> directory) { this.directory = directory; }

    /**
     * The file {@code name} denotes inside the directory. For a save it may not exist yet (the directory is
     * created if needed, as {@code //schem save} does); for a load it must exist.
     */
    public Path resolve(String name, boolean forSave) {
        Validation.schematicName(name);
        try {
            Path dir = directory.get();
            if (forSave) Files.createDirectories(dir);
            else if (!Files.isDirectory(dir)) throw missing(name);
            Path realDir = dir.toRealPath();
            Path file = realDir.resolve(name);
            if (!realDir.equals(file.getParent())) throw ApiException.badRequest("Schematic must be a plain file name: " + name);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw ApiException.badRequest("Schematic " + name + " is a link or not a regular file; only plain files in the schematics folder are used");
            } else if (!forSave) {
                throw missing(name);
            }
            return file;
        } catch (IOException e) {
            throw new ApiException(500, "ACTION_FAILED", "Cannot use the schematics folder: " + e.getMessage());
        }
    }

    private static ApiException missing(String name) {
        return ApiException.notFound("No such schematic in WorldEdit's schematics folder: " + name);
    }
}

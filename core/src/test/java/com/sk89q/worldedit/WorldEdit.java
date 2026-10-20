package com.sk89q.worldedit;

import com.sk89q.worldedit.extension.factory.MaskFactory;
import com.sk89q.worldedit.extension.factory.PatternFactory;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.session.SessionManager;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Test double for WorldEdit's entry point; only the FQN and the methods the adapter calls matter. */
public final class WorldEdit {
    private static final WorldEdit INSTANCE = new WorldEdit();
    private final PatternFactory patternFactory = new PatternFactory();
    private final MaskFactory maskFactory = new MaskFactory();

    public static WorldEdit getInstance() { return INSTANCE; }
    public static String getVersion() { return FakeWorldEdit.INSTANCE.version; }

    /** Sessions live in FakeWorldEdit so FakeWorldEdit.reset() gives every test fresh ones. */
    public SessionManager getSessionManager() { return FakeWorldEdit.INSTANCE.sessionManager; }
    public PatternFactory getPatternFactory() { return patternFactory; }
    public MaskFactory getMaskFactory() { return maskFactory; }
    public LocalConfiguration getConfiguration() { return FakeWorldEdit.INSTANCE.configuration; }

    /** Mirrors the real method: an absolute path is used as is, a relative one under the working directory. */
    public Path getWorkingDirectoryPath(String path) {
        Path p = Paths.get(path);
        return p.isAbsolute() ? p : FakeWorldEdit.INSTANCE.workingDirectory.resolve(path);
    }

    /** Mirrors the real check: only a positive configured maxRadius limits anything. */
    public void checkMaxRadius(double radius) throws MaxRadiusException {
        FakeWorldEdit.INSTANCE.calls.add("checkMaxRadius:" + radius);
        int max = FakeWorldEdit.INSTANCE.maxRadius;
        if (max > 0 && radius > max) throw new MaxRadiusException();
    }

    public void flushBlockBag(Actor actor, EditSession editSession) {
        FakeWorldEdit.INSTANCE.calls.add("flushBlockBag:" + actor + ":" + editSession.blockBag);
    }
}

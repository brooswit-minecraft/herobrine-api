package com.sk89q.worldedit;

import com.sk89q.worldedit.extension.factory.MaskFactory;
import com.sk89q.worldedit.extension.factory.PatternFactory;
import com.sk89q.worldedit.session.SessionManager;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;

/** Test double for WorldEdit's entry point; only the FQN and the methods the adapter calls matter. */
public final class WorldEdit {
    private static final WorldEdit INSTANCE = new WorldEdit();
    private final SessionManager sessionManager = new SessionManager();
    private final PatternFactory patternFactory = new PatternFactory();
    private final MaskFactory maskFactory = new MaskFactory();

    public static WorldEdit getInstance() { return INSTANCE; }
    public static String getVersion() { return FakeWorldEdit.INSTANCE.version; }

    public SessionManager getSessionManager() { return sessionManager; }
    public PatternFactory getPatternFactory() { return patternFactory; }
    public MaskFactory getMaskFactory() { return maskFactory; }
}

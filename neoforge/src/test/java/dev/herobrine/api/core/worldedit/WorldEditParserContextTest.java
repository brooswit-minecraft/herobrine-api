package dev.herobrine.api.core.worldedit;

import static org.junit.jupiter.api.Assertions.*;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extension.factory.parser.mask.ExistingMaskParser;
import com.sk89q.worldedit.extension.factory.parser.mask.RegionMaskParser;
import com.sk89q.worldedit.extension.factory.parser.pattern.ClipboardPatternParser;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.mask.ExistingBlockMask;
import com.sk89q.worldedit.function.mask.RegionMask;
import com.sk89q.worldedit.function.pattern.ClipboardPattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.limit.PermissiveSelectorLimits;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.NullWorld;
import com.sk89q.worldedit.world.World;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

/**
 * The adapter's {@code ParserContext}, built by the production {@link ReflectiveWorldEdit#parserContext}, fed to
 * WorldEdit 7.3.8's own parsers - {@code ClipboardPatternParser} ({@code #clipboard}, {@code #copy}),
 * {@code RegionMaskParser} ({@code #region}, {@code #selection}, {@code #sel}) and {@code ExistingMaskParser} (a
 * mask tested against the context's extent) - with WorldEdit's real {@code LocalSession}, selections, clipboards
 * and worlds. Those classes are on this module's test classpath only, which is why this test of a core class
 * lives here, in its package. The parsers never touch their {@code WorldEdit} instance, so none is created.
 */
class WorldEditParserContextTest {
    final ReflectiveWorldEdit adapter = new ReflectiveWorldEdit(getClass().getClassLoader());
    /** Two worlds that are not equal to each other, as two loaded dimensions are not. */
    final World overworld = new NullWorld() {}, nether = new NullWorld() {};
    /** Stands in for the operation's EditSession: the extent masks must test blocks against. */
    final Extent editSession = new NullExtent();
    final Player steve = player("steve", overworld), alex = player("alex", overworld);
    LocalSession steveSession, alexSession;

    @BeforeEach void setUp() {
        steveSession = new LocalSession();
        alexSession = new LocalSession();
    }

    /** A WorldEdit player standing in {@code world} - the shape {@code NeoForgeAdapter.adaptPlayer} hands out. */
    static Player player(String name, World world) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (self, m, args) ->
                switch (m.getName()) {
                    case "getExtent", "getWorld" -> world;
                    case "hasPermission" -> true;
                    case "toString" -> "Player(" + name + ")";
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    ParserContext context(LocalSession session, Player actor) {
        return (ParserContext) adapter.parserContext(session, actor, editSession);
    }

    static Clipboard clipboard(LocalSession session, BlockVector3 min, BlockVector3 max) {
        Clipboard clipboard = new BlockArrayClipboard(new CuboidRegion(min, max));
        session.setClipboard(new ClipboardHolder(clipboard));
        return clipboard;
    }

    static void select(LocalSession session, World world, BlockVector3 a, BlockVector3 b) {
        RegionSelector selector = session.getRegionSelector(world);
        selector.selectPrimary(a, PermissiveSelectorLimits.getInstance());
        selector.selectSecondary(b, PermissiveSelectorLimits.getInstance());
    }

    static Region region(Object mask) { return ((RegionMask) mask).getRegion(); }

    /**
     * Asserts that {@code parser} itself refused the input with the {@code InputParseException} it raises for
     * missing data (an empty clipboard, no selection in that world) - not {@code ParserContext} refusing for a
     * missing session or world. WorldEdit builds that exception's message through its translation manager, which
     * needs a running platform; this test has none, so constructing it throws {@code NoCapablePlatformException}
     * instead, and the frame that called the {@code InputParseException} constructor shows whose refusal it was.
     */
    static void refusedBy(Class<?> parser, Executable parse) {
        Throwable t = assertThrows(Throwable.class, parse);
        StackTraceElement[] frames = t.getStackTrace();
        if (t instanceof InputParseException) {
            assertEquals(parser.getName(), frames[0].getClassName(), "refused by " + frames[0]);
            return;
        }
        for (int i = 0; i < frames.length - 1; i++) {
            if (frames[i].getClassName().equals(InputParseException.class.getName()) && frames[i].getMethodName().equals("<init>")) {
                assertEquals(parser.getName(), frames[i + 1].getClassName(), "refused by " + frames[i + 1]);
                return;
            }
        }
        fail("not refused with an InputParseException: " + t);
    }

    @Test void theContextCarriesTheActorItsSessionItsWorldAndTheOperationsExtent() {
        ParserContext ctx = context(steveSession, steve);
        assertSame(steve, ctx.getActor());
        assertSame(steveSession, ctx.getSession());
        assertSame(overworld, ctx.getWorld());
        assertSame(editSession, ctx.getExtent(), "setWorld resets the extent to the world; the EditSession must win");
        assertTrue(ctx.isRestricted());
    }

    @Test void clipboardPatternsReadTheActorsOwnClipboard() throws Exception {
        Clipboard steves = clipboard(steveSession, BlockVector3.at(0, 0, 0), BlockVector3.at(1, 1, 1));
        Clipboard alexs = clipboard(alexSession, BlockVector3.at(10, 10, 10), BlockVector3.at(12, 12, 12));
        ClipboardPatternParser parser = new ClipboardPatternParser(null);
        assertSame(steves, ((ClipboardPattern) parser.parseFromInput("#clipboard", context(steveSession, steve))).getExtent());
        assertSame(steves, ((ClipboardPattern) parser.parseFromInput("#copy@[1,0,1]", context(steveSession, steve))).getExtent());
        assertSame(alexs, ((ClipboardPattern) parser.parseFromInput("#clipboard", context(alexSession, alex))).getExtent());

        alexSession.setClipboard(null); // alex's own clipboard is empty now: steve's is never reached instead
        refusedBy(ClipboardPatternParser.class, () -> parser.parseFromInput("#clipboard", context(alexSession, alex)));
    }

    @Test void selectionMasksReadTheActorsOwnSelectionInTheWorldBeingEdited() throws Exception {
        select(steveSession, overworld, BlockVector3.at(1, 2, 3), BlockVector3.at(4, 5, 6));
        select(alexSession, overworld, BlockVector3.at(7, 7, 7), BlockVector3.at(8, 8, 8));
        RegionMaskParser parser = new RegionMaskParser(null);
        for (String alias : new String[]{"#region", "#selection", "#sel"}) {
            Region r = region(parser.parseFromInput(alias, context(steveSession, steve)));
            assertEquals(BlockVector3.at(1, 2, 3), r.getMinimumPoint(), alias);
            assertEquals(BlockVector3.at(4, 5, 6), r.getMaximumPoint(), alias);
        }
        assertEquals(BlockVector3.at(7, 7, 7), region(parser.parseFromInput("#region", context(alexSession, alex))).getMinimumPoint());

        // steve has moved to the nether: his overworld selection is not the selection there...
        Player steveInNether = player("steve", nether);
        refusedBy(RegionMaskParser.class, () -> parser.parseFromInput("#region", context(steveSession, steveInNether)));
        // ...unless his session's world override still points his edits at the overworld
        steveSession.setWorldOverride(overworld);
        assertSame(overworld, context(steveSession, steveInNether).getWorld());
        assertEquals(BlockVector3.at(4, 5, 6), region(parser.parseFromInput("#region", context(steveSession, steveInNether))).getMaximumPoint());

        // a session without any selection refuses rather than borrowing another player's
        refusedBy(RegionMaskParser.class, () -> parser.parseFromInput("#region", context(new LocalSession(), alex)));
    }

    @Test void extentBackedMasksTestBlocksAgainstTheOperationsEditSession() throws Exception {
        Object existing = new ExistingMaskParser(null).parseFromInput("#existing", context(steveSession, steve));
        assertSame(editSession, ((ExistingBlockMask) existing).getExtent());
    }
}

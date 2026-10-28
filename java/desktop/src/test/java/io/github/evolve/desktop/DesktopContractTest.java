package io.github.evolve.desktop;

import io.github.evolve.core.EvolutionEngine;
import io.github.evolve.core.GameSave;
import io.github.evolve.core.GameCommand;
import io.github.evolve.core.GameSnapshot;
import io.github.evolve.core.SaveStore;
import io.github.evolve.core.TextRef;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DesktopContractTest {
    @Test
    void buttonsOperateOnRestoredSessionAndBadLoadKeepsLiveSession() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var store = new MemoryStore();
            var panel = new EvolvePanel(EvolutionEngine.newGame(2), store, "zh_cn");
            panel.act("evolution-rna", 10);
            assertTrue(panel.saveGame());
            panel.act("evolution-rna", 10);
            panel.loadGame();
            panel.actionButton("evolution-rna").doClick();
            assertEquals(11, panel.engine().snapshot().resources().getFirst().amount());
            var before = panel.engine().exportSave();
            store.broken = true;
            panel.loadGame();
            assertEquals(before, panel.engine().exportSave());
        });
    }

    @Test
    void translationsSubstituteArgumentsLiterallyAndFallbackKeysRemainDetectable() {
        var english = new Localizer("en_us");
        var chinese = new Localizer("zh_cn");
        assertEquals("Evolution: 42%", english.text("ui_progress", 42));
        assertEquals("演化进度：42%", chinese.text("ui_progress", 42));
        assertTrue(english.text(TextRef.of("evo_rna", "$1\\value")).contains("$1\\value"));
        assertEquals("evolve.missing_key", chinese.text("missing_key"));
    }

    @Test
    void civilizationButtonsUseDynamicResourcesAndTheRestoredSession() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var store = new MemoryStore();
            var panel = new EvolvePanel(EvolutionEngine.restore(EvolveDesktop.civilizationHandoffForSmoke()), store, "zh_cn");
            panel.actionButton("civilization-start").doClick();
            assertEquals(GameSnapshot.Phase.PRIMITIVE, panel.engine().snapshot().phase());
            panel.act("city-lumber", 5);
            assertFalse(panel.actionButton("city-lumber").getText().contains("RNA"));
            panel.actionButton("tech-club").doClick();
            assertNotNull(panel.actionButton("city-food"));
            assertTrue(panel.saveGame());
            panel.act("city-food", 10);
            panel.loadGame();
            panel.actionButton("city-food").doClick();
            assertEquals(1, panel.engine().exportSave().resources().get("Food").amount());
            assertDoesNotThrow(() -> { panel.setSize(1120, 760); panel.doLayout(); panel.refresh(); });
            var before = panel.engine().exportSave();
            store.broken = true;
            panel.loadGame();
            assertEquals(before, panel.engine().exportSave());
        });
    }

    @Test
    void primitiveTextsAreAvailableInBothLanguagesThroughoutProgression() {
        var game = EvolutionEngine.restore(EvolveDesktop.civilizationHandoffForSmoke());
        translated(game.snapshot());
        game.execute(new GameCommand.Action("civilization-start"));
        translated(game.snapshot());
        game.execute(new GameCommand.Action("city-lumber", 5));
        game.execute(new GameCommand.Action("tech-club"));
        translated(game.snapshot());
        game.execute(new GameCommand.Action("city-food", 10));
        game.execute(new GameCommand.Action("tech-bone_tools"));
        translated(game.snapshot());
        game.execute(new GameCommand.Action("city-lumber", 18));
        game.execute(new GameCommand.Action("city-stone", 10));
        game.execute(new GameCommand.Action("tech-sundial"));
        game.advanceTicks(200);
        translated(game.snapshot());
        game.execute(new GameCommand.Action("tech-housing"));
        game.execute(new GameCommand.Action("city-basic_housing"));
        translated(game.snapshot());
    }

    private static void translated(GameSnapshot snapshot) {
        for (String locale : java.util.List.of("en_us", "zh_cn")) {
            var localizer = new Localizer(locale);
            snapshot.resources().forEach(r -> assertFalse(localizer.text(r.name()).startsWith("evolve."), r.name().key()));
            snapshot.jobs().forEach(j -> assertFalse(localizer.text(j.name()).startsWith("evolve."), j.name().key()));
            snapshot.actions().forEach(action -> {
                assertFalse(localizer.text(action.title()).startsWith("evolve."), action.title().key());
                action.tooltip().forEach(t -> assertFalse(localizer.text(t).startsWith("evolve."), t.key()));
            });
        }
    }

    private static final class MemoryStore implements SaveStore {
        GameSave saved;
        boolean broken;
        @Override public Optional<GameSave> load() throws IOException {
            if (broken) throw new IOException("Invalid save");
            return Optional.ofNullable(saved);
        }
        @Override public void save(GameSave save) { saved = save; }
    }
}

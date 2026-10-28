package io.github.evolve.desktop;

import io.github.evolve.core.EvolutionEngine;
import io.github.evolve.core.GameSave;
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

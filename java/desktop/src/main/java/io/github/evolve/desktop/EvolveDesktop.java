package io.github.evolve.desktop;

import io.github.evolve.core.EvolutionEngine;
import io.github.evolve.core.GameCommand;
import io.github.evolve.core.GameSave;
import io.github.evolve.core.GameSnapshot;
import io.github.evolve.core.CivilizationState;
import io.github.evolve.core.SpeciesCatalog;
import io.github.evolve.storage.AtomicFileSaveStore;
import io.github.evolve.storage.JsonSaveCodec;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Container;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EvolveDesktop {
    private EvolveDesktop() {}

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--smoke-test")) { smokeTest(); return; }
        String locale = Arrays.asList(args).contains("--english") ? "en_us" : "zh_cn";
        Path savePath = Path.of(Arrays.stream(args).filter(arg -> arg.startsWith("--save="))
                .map(arg -> arg.substring("--save=".length())).findFirst().orElse("evolve.save.json"));
        SwingUtilities.invokeLater(() -> {
            var panel = new EvolvePanel(EvolutionEngine.newGame((int) System.nanoTime()),
                    new AtomicFileSaveStore(savePath, new JsonSaveCodec()), locale);
            var frame = new JFrame(new Localizer(locale).text("ui_title"));
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setContentPane(panel);
            frame.setSize(1120, 760);
            frame.setMinimumSize(new java.awt.Dimension(900, 560));
            frame.setLocationByPlatform(true);
            var timer = new Timer(50, event -> panel.tick());
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) {
                    timer.stop();
                    if (panel.dirty()) {
                        int answer = JOptionPane.showConfirmDialog(frame, panel.uiText("ui_close"),
                                panel.uiText("ui_confirm_title"), JOptionPane.YES_NO_CANCEL_OPTION);
                        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION ||
                                (answer == JOptionPane.YES_OPTION && !panel.saveGame())) { timer.start(); return; }
                    }
                    frame.dispose();
                }
            });
            frame.setVisible(true);
            timer.start();
        });
    }

    /** Exercises the actual GUI buttons and storage, then renders without opening a window. */
    private static void smokeTest() throws Exception {
        Path directory = Files.createTempDirectory("evolve-gui-smoke-");
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var game = EvolutionEngine.newGame(2);
                    var panel = new EvolvePanel(game, new AtomicFileSaveStore(directory.resolve("game.json"), new JsonSaveCodec()), "zh_cn");
                    panel.act("evolution-rna", 100);
                    game.advanceTicks(10);
                    panel.refresh();
                    panel.act("evolution-dna", 40);
                    game.advanceTicks(5);
                    panel.refresh();
                    panel.actionButton("evolution-organelles").doClick();
                    panel.act("evolution-rna", 100);
                    panel.actionButton("evolution-organelles").doClick();
                    game.advanceTicks(5);
                    panel.refresh();
                    panel.actionButton("evolution-nucleus").doClick();
                    game.advanceTicks(20);
                    panel.refresh();
                    if (game.exportSave().structures().get("nucleus") != 1) throw new IllegalStateException("GUI action failed");
                    if (!panel.saveGame()) throw new IllegalStateException("GUI save failed");
                    var saved = game.exportSave();
                    panel.act("evolution-rna", 10);
                    panel.loadGame();
                    if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("GUI load failed");
                    render(panel, Path.of("build/preview.png"), 760);
                    civilizationSmokeTest(directory);
                } catch (Exception e) { throw new IllegalStateException("GUI smoke test failed", e); }
            });
        } finally {
            Files.deleteIfExists(directory.resolve("game.json.bak"));
            Files.deleteIfExists(directory.resolve("game.json"));
            Files.deleteIfExists(directory.resolve("civilization.json.bak"));
            Files.deleteIfExists(directory.resolve("civilization.json"));
            Files.deleteIfExists(directory);
        }
    }

    /** Valid handoff fixture; the core tests separately cover real evolution into this phase. */
    static GameSave civilizationHandoffForSmoke() {
        var fresh = EvolutionEngine.newGame(2).exportSave();
        var resources = new LinkedHashMap<String, GameSave.ResourceState>();
        fresh.resources().forEach((id, r) -> resources.put(id, new GameSave.ResourceState(r.amount(), r.capacity(), false, 0)));
        return new GameSave(GameSave.SCHEMA_VERSION, GameSave.RULESET, 0, 0, 0, 0, false,
                GameSnapshot.Phase.CIVILIZATION_READY, 2, "human", "humanoid", 100, fresh.context(), resources,
                Map.of(), Map.of(), SpeciesCatalog.startingTraits("human"), CivilizationState.handoff("humanoid"));
    }

    private static void civilizationSmokeTest(Path directory) throws Exception {
        var panel = new EvolvePanel(EvolutionEngine.restore(civilizationHandoffForSmoke()),
                new AtomicFileSaveStore(directory.resolve("civilization.json"), new JsonSaveCodec()), "zh_cn");
        panel.actionButton("civilization-start").doClick();
        panel.act("city-lumber", 5);
        panel.actionButton("tech-club").doClick();
        panel.act("city-food", 10);
        panel.actionButton("tech-bone_tools").doClick();
        panel.act("city-lumber", 100);
        panel.act("city-stone", 10);
        panel.actionButton("tech-sundial").doClick();
        panel.engine().advanceTicks(203);
        panel.refresh();
        panel.actionButton("tech-housing").doClick();
        panel.act("city-basic_housing", 5);
        panel.act("city-food", 100);
        panel.engine().advanceTicks(20);
        panel.refresh();
        if (panel.engine().snapshot().jobs().getFirst().workers() == 0) throw new IllegalStateException("Population growth failed");
        if (panel.actionButton("city-basic_housing").isEnabled()) throw new IllegalStateException("Currency boundary not enforced");
        if (!panel.saveGame()) throw new IllegalStateException("Civilization save failed");
        var saved = panel.engine().exportSave();
        panel.act("city-food", 5);
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Civilization load failed");
        panel.actionButton("city-lumber").doClick();
        if (panel.engine().exportSave().resources().get("Lumber").amount() != saved.resources().get("Lumber").amount() + 1)
            throw new IllegalStateException("Civilization button used old session");
        render(panel, Path.of("build/preview-civilization.png"), 900);
        workingSettlementSmokeTest(panel);
    }

    private static void workingSettlementSmokeTest(EvolvePanel panel) throws Exception {
        waitFor(panel, "Knowledge", 10);
        panel.actionButton("tech-agriculture").doClick();
        panel.act("city-lumber", 100);
        panel.act("city-stone", 100);
        panel.act("city-farm", 3);
        for (int i = 0; i < 3; i++) panel.jobButton("farmer", true).doClick();
        waitFor(panel, "Knowledge", 45);
        panel.actionButton("tech-stone_axe").doClick();
        panel.jobButton("lumberjack", true).doClick();
        waitFor(panel, "Knowledge", 45);
        panel.actionButton("tech-mining").doClick();
        waitFor(panel, "Lumber", 50);
        panel.actionButton("city-rock_quarry").doClick();
        panel.jobButton("quarry_worker", true).doClick();
        waitFor(panel, "Knowledge", 22);
        waitFor(panel, "Lumber", 10);
        panel.actionButton("tech-currency").doClick();
        waitFor(panel, "Money", 44);
        waitFor(panel, "Lumber", 28);
        panel.actionButton("city-basic_housing").doClick();
        waitFor(panel, "Knowledge", 55);
        panel.actionButton("tech-irrigation").doClick();
        waitFor(panel, "Lumber", 6);
        panel.actionButton("city-lumber_yard").doClick();
        panel.engine().advanceTicks(20);
        panel.refresh();
        if (panel.engine().exportSave().structures().get("basic_housing") != 6 ||
                panel.engine().exportSave().civilization().workers().get("farmer") != 3 ||
                panel.engine().exportSave().civilization().workers().get("lumberjack") != 1 ||
                panel.engine().exportSave().civilization().workers().get("quarry_worker") != 1)
            throw new IllegalStateException("Working settlement GUI failed");
        if (!panel.saveGame()) throw new IllegalStateException("Working settlement save failed");
        var saved = panel.engine().exportSave();
        panel.jobButton("lumberjack", false).doClick();
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Working settlement load failed");
        panel.jobButton("lumberjack", false).doClick();
        if (panel.engine().exportSave().civilization().workers().get("lumberjack") != 0)
            throw new IllegalStateException("Job button used old session");
        panel.jobButton("lumberjack", true).doClick();
        render(panel, Path.of("build/preview-jobs.png"), 900);
        researchSettlementSmokeTest(panel);
    }

    static void researchSettlementSmokeTest(EvolvePanel panel) throws Exception {
        waitFor(panel, "Knowledge", 20);
        panel.actionButton("tech-storage").doClick();
        waitFor(panel, "Money", 75);
        waitFor(panel, "Lumber", 55);
        waitFor(panel, "Stone", 45);
        panel.actionButton("city-shed").doClick();
        waitFor(panel, "Knowledge", 65);
        panel.actionButton("tech-science").doClick();
        waitFor(panel, "Money", 400);
        waitFor(panel, "Lumber", 300);
        waitFor(panel, "Stone", 400);
        panel.actionButton("city-university").doClick();
        waitFor(panel, "human", 6);
        panel.jobButton("professor", true).doClick();
        if (panel.jobButton("professor", true).isEnabled() || !panel.jobButton("professor", false).isEnabled())
            throw new IllegalStateException("Professor capacity controls failed");
        panel.engine().advanceTicks(20);
        panel.refresh();
        if (panel.engine().exportSave().resources().get("Knowledge").capacity() != 600 ||
                panel.engine().exportSave().resources().get("Knowledge").changePerSecond() != 1.5)
            throw new IllegalStateException("University research production failed");
        if (!panel.saveGame()) throw new IllegalStateException("Science save failed");
        var saved = panel.engine().exportSave();
        panel.jobButton("professor", false).doClick();
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Science load failed");
        panel.jobButton("professor", false).doClick();
        if (panel.engine().exportSave().civilization().workers().get("professor") != 0)
            throw new IllegalStateException("Professor button used old session");
        panel.jobButton("professor", true).doClick();
        render(panel, Path.of("build/preview-science.png"), 900);
        bankingSettlementSmokeTest(panel);
    }

    private static void bankingSettlementSmokeTest(EvolvePanel panel) throws Exception {
        buyWhenReady(panel, "tech-banking");
        buyWhenReady(panel, "city-bank");
        if (panel.engine().exportSave().resources().get("Money").capacity() != 2800)
            throw new IllegalStateException("Bank storage failed");
        if (panel.engine().snapshot().jobs().stream().anyMatch(job -> job.id().equals("banker")))
            throw new IllegalStateException("Bankers must require investing");
        buyWhenReady(panel, "city-shed");
        buyWhenReady(panel, "city-university");
        buyWhenReady(panel, "tech-investing");
        buyWhenReady(panel, "city-basic_housing");
        waitFor(panel, "human", 7);
        panel.jobButton("banker", true).doClick();
        if (panel.jobButton("banker", true).isEnabled() || !panel.jobButton("banker", false).isEnabled())
            throw new IllegalStateException("Banker capacity controls failed");
        panel.engine().advanceTicks(20);
        panel.refresh();
        if (Math.abs(panel.engine().exportSave().resources().get("Money").changePerSecond() - 3.08) > 1e-9)
            throw new IllegalStateException("Banker income failed");
        if (!panel.saveGame()) throw new IllegalStateException("Banking save failed");
        var saved = panel.engine().exportSave();
        panel.jobButton("banker", false).doClick();
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Banking load failed");
        panel.jobButton("banker", false).doClick();
        if (panel.engine().exportSave().civilization().workers().get("banker") != 0)
            throw new IllegalStateException("Banker button used old session");
        panel.jobButton("banker", true).doClick();
        render(panel, Path.of("build/preview-banking.png"), 960);
        copperSettlementSmokeTest(panel);
    }

    private static void copperSettlementSmokeTest(EvolvePanel panel) throws Exception {
        buyWhenReady(panel, "tech-metal_working");
        buyWhenReady(panel, "city-mine");
        // Reassign the banker while food production supports seven working citizens.
        panel.jobButton("banker", false).doClick();
        panel.jobButton("miner", true).doClick();
        if (panel.jobButton("miner", true).isEnabled() || !panel.jobButton("miner", false).isEnabled())
            throw new IllegalStateException("Miner capacity controls failed");
        buyWhenReady(panel, "tech-copper_axes");
        buyWhenReady(panel, "tech-copper_pickaxe");
        panel.engine().advanceTicks(20);
        panel.refresh();
        if (Math.abs(panel.engine().exportSave().resources().get("Copper").changePerSecond() - (1.15 / 7)) > 1e-9 ||
                Math.abs(panel.engine().exportSave().resources().get("Lumber").changePerSecond() - 1.35 * 1.02) > 1e-9)
            throw new IllegalStateException("Copper tools production failed");
        if (!panel.saveGame()) throw new IllegalStateException("Copper save failed");
        var saved = panel.engine().exportSave();
        panel.jobButton("miner", false).doClick();
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Copper load failed");
        panel.jobButton("miner", false).doClick();
        if (panel.engine().exportSave().civilization().workers().get("miner") != 0)
            throw new IllegalStateException("Miner button used old session");
        panel.jobButton("miner", true).doClick();
        render(panel, Path.of("build/preview-copper.png"), 1020);
        ironSettlementSmokeTest(panel);
    }

    private static void ironSettlementSmokeTest(EvolvePanel panel) throws Exception {
        buyWithStorage(panel, "city-farm");
        buyWithStorage(panel, "city-farm");
        for (int i = 0; i < 3; i++) buyWithStorage(panel, "city-basic_housing");
        waitFor(panel, "human", 10);
        for (int i = 0; i < 2; i++) panel.jobButton("farmer", true).doClick();
        panel.jobButton("banker", true).doClick();
        buyWithStorage(panel, "tech-iron_mining");
        if (panel.engine().exportSave().resources().get("Iron").capacity() != 100 || !panel.saveGame())
            throw new IllegalStateException("Pending iron capacity save failed");
        var pending = panel.engine().exportSave();
        panel.loadGame();
        if (!pending.equals(panel.engine().exportSave())) throw new IllegalStateException("Iron load applied capacity early");
        buyWithStorage(panel, "tech-iron_axes");
        buyWithStorage(panel, "tech-iron_pickaxe");
        panel.engine().advanceTicks(20);
        panel.refresh();
        if (Math.abs(panel.engine().exportSave().resources().get("Iron").changePerSecond() - 1.3 * 0.25) > 1e-9 ||
                Math.abs(panel.engine().exportSave().resources().get("Copper").changePerSecond() - 1.3 / 7) > 1e-9 ||
                Math.abs(panel.engine().exportSave().resources().get("Lumber").changePerSecond() - 1.7 * 1.02) > 1e-9)
            throw new IllegalStateException("Iron tools production failed");
        if (!panel.saveGame()) throw new IllegalStateException("Iron tools save failed");
        var saved = panel.engine().exportSave();
        panel.jobButton("miner", false).doClick();
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Iron tools load failed");
        panel.jobButton("miner", false).doClick();
        if (panel.engine().exportSave().civilization().workers().get("miner") != 0)
            throw new IllegalStateException("Iron miner button used old session");
        panel.jobButton("miner", true).doClick();
        render(panel, Path.of("build/preview-iron.png"), 1100);
        smeltingSettlementSmokeTest(panel);
    }

    private static void smeltingSettlementSmokeTest(EvolvePanel panel) throws Exception {
        buyWithStorage(panel, "tech-smelting");
        buyWithStorage(panel, "city-smelter");
        panel.jobButton("banker", false).doClick();
        panel.jobButton("lumberjack", true).doClick();
        waitFor(panel, "Lumber", 10);
        panel.engine().advanceTicks(20);
        panel.refresh();
        if (Math.abs(panel.engine().exportSave().resources().get("Iron").changePerSecond() - 1.3 * 0.25 * 1.1) > 1e-9)
            throw new IllegalStateException("Wood smelting failed");
        if (panel.smelterButton("Wood", true).isEnabled() || !panel.smelterButton("Wood", false).isEnabled())
            throw new IllegalStateException("Smelter capacity controls failed");
        panel.smelterButton("Wood", false).doClick();
        if (panel.engine().snapshot().smelter().wood() != 0 || panel.engine().snapshot().smelter().iron() != 0)
            throw new IllegalStateException("Removing fuel did not release iron slots");
        panel.smelterButton("Wood", true).doClick();
        panel.smelterButton("Iron", false).doClick();
        if (!panel.saveGame()) throw new IllegalStateException("Smelter save failed");
        var saved = panel.engine().exportSave();
        panel.smelterButton("Iron", true).doClick();
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Smelter load altered allocation");
        panel.smelterButton("Wood", false).doClick();
        if (panel.engine().snapshot().smelter().wood() != 0) throw new IllegalStateException("Smelter button used old session");
        panel.smelterButton("Wood", true).doClick();
        panel.engine().execute(new GameCommand.Pause(true));
        panel.refresh();
        if (panel.smelterButton("Wood", false).isEnabled()) throw new IllegalStateException("Paused smelter control enabled");
        panel.engine().execute(new GameCommand.Pause(false));
        panel.engine().advanceTicks(20);
        panel.refresh();
        render(panel, Path.of("build/preview-smelting.png"), 1300);
        cementSettlementSmokeTest(panel);
    }

    private static void cementSettlementSmokeTest(EvolvePanel panel) throws Exception {
        panel.smelterButton("Wood", false).doClick();
        buyWithStorage(panel, "tech-cement");
        buyWithStorage(panel, "city-cement_plant");
        if (panel.engine().exportSave().resources().get("Cement").capacity() != 100)
            throw new IllegalStateException("Cement storage applied too early");
        buyWithStorage(panel, "tech-iron_saw");
        waitFor(panel, "Stone", 3000);
        panel.jobButton("lumberjack", false).doClick();
        panel.jobButton("cement_worker", true).doClick();
        panel.engine().advanceTicks(20);
        panel.refresh();
        if (Math.abs(panel.engine().exportSave().resources().get("Cement").changePerSecond() - 0.4) > 1e-9)
            throw new IllegalStateException("Cement production failed");
        buyWithStorage(panel, "city-sawmill");
        panel.engine().advanceTicks(20);
        panel.refresh();
        double lumber = 1.7 * 1.05 * (1 + panel.engine().exportSave().structures().get("lumber_yard") * 0.02);
        if (Math.abs(panel.engine().exportSave().resources().get("Lumber").changePerSecond() - lumber) > 1e-9)
            throw new IllegalStateException("Sawmill production failed");
        if (!panel.saveGame()) throw new IllegalStateException("Cement save failed");
        var saved = panel.engine().exportSave();
        panel.jobButton("cement_worker", false).doClick();
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Cement load failed");
        panel.jobButton("cement_worker", false).doClick();
        if (panel.engine().exportSave().civilization().workers().get("cement_worker") != 0)
            throw new IllegalStateException("Cement worker button used old session");
        panel.jobButton("cement_worker", true).doClick();
        render(panel, Path.of("build/preview-cement.png"), 1400);
        farmStorageSmokeTest(panel);
    }

    private static void farmStorageSmokeTest(EvolvePanel panel) throws Exception {
        panel.jobButton("cement_worker", false).doClick();
        double houses = panel.engine().exportSave().resources().get("human").capacity();
        buyWithStorage(panel, "tech-farm_house");
        if (panel.engine().exportSave().resources().get("human").capacity() != houses)
            throw new IllegalStateException("Farm housing applied before midLoop");
        if (!panel.saveGame()) throw new IllegalStateException("Pending farm housing save failed");
        panel.loadGame();
        if (panel.engine().exportSave().resources().get("human").capacity() != houses)
            throw new IllegalStateException("Loading applied pending farm housing");
        panel.actionButton("city-farm").doClick();
        if (panel.engine().exportSave().resources().get("human").capacity() != houses + 1)
            throw new IllegalStateException("New farm housing did not apply immediately");
        panel.engine().advanceTicks(20);
        waitFor(panel, "human", houses + 1);
        panel.jobButton("farmer", true).doClick();
        waitFor(panel, "Stone", 3000);
        panel.jobButton("cement_worker", true).doClick();
        waitFor(panel, "Cement", 500);
        panel.jobButton("cement_worker", false).doClick();
        panel.jobButton("quarry_worker", true).doClick();
        panel.jobButton("lumberjack", true).doClick();
        for (int expansions = 0; !panel.engine().snapshot().actions().stream().filter(a -> a.id().equals("city-shed")).findFirst().orElseThrow().fitsStorage(); expansions++) {
            if (expansions >= 50) throw new IllegalStateException("Pending reinforced shed expansion stalled");
            buyWithStorage(panel, "city-lumber_yard");
        }
        var shed = panel.engine().snapshot().actions().stream().filter(a -> a.id().equals("city-shed")).findFirst().orElseThrow();
        shed.costs().forEach((id, amount) -> waitFor(panel, id, amount + (id.equals("Money") ? 3750 : 0)));
        waitFor(panel, "Knowledge", 2550);
        waitFor(panel, "Iron", 750);
        var before = panel.engine().exportSave();
        panel.actionButton("tech-reinforced_shed").doClick();
        if (panel.engine().exportSave().resources().get("Lumber").capacity() != before.resources().get("Lumber").capacity())
            throw new IllegalStateException("Reinforced storage applied before midLoop");
        panel.actionButton("city-shed").doClick();
        if (panel.engine().exportSave().resources().get("Lumber").capacity() != before.resources().get("Lumber").capacity() + 675)
            throw new IllegalStateException("New reinforced shed did not apply immediately");
        if (!panel.saveGame()) throw new IllegalStateException("Mixed storage save failed");
        var saved = panel.engine().exportSave();
        panel.engine().advanceTicks(20);
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Loading applied pending reinforced storage");
        panel.engine().advanceTicks(20);
        panel.refresh();
        int sheds = panel.engine().exportSave().structures().get("shed");
        if (panel.engine().exportSave().resources().get("Copper").capacity() != 100 + 203d * sheds ||
                panel.engine().exportSave().resources().get("Iron").capacity() != 100 + 281d * sheds)
            throw new IllegalStateException("Reinforced shed rounding failed");
        render(panel, Path.of("build/preview-farm-storage.png"), 1400);
        craftingSmokeTest(panel);
    }

    private static void craftingSmokeTest(EvolvePanel panel) throws Exception {
        buyWithStorage(panel, "city-farm");
        buyWithStorage(panel, "city-farm");
        waitFor(panel, "human", 18);
        panel.jobButton("farmer", true).doClick();
        panel.jobButton("farmer", true).doClick();
        buyWithStorage(panel, "tech-foundry");
        for (int i = 0; i < 3; i++) buyWithStorage(panel, "city-foundry");
        buyWithStorage(panel, "tech-artisans");
        panel.jobButton("cement_worker", true).doClick();
        waitFor(panel, "Cement", 44);
        for (String id : java.util.List.of("Plywood", "Brick", "Wrought_Iron")) {
            buyWithStorage(panel, "craft-" + id);
            if (Math.abs(panel.engine().exportSave().resources().get(id).amount() - 1.09) > 1e-9)
                throw new IllegalStateException("Manual crafting failed: " + id);
            panel.jobButton("craft_" + id, true).doClick();
        }
        panel.engine().advanceTicks(20);
        panel.refresh();
        for (String id : java.util.List.of("Plywood", "Brick", "Wrought_Iron")) {
            if (Math.abs(panel.engine().exportSave().resources().get(id).changePerSecond() - 1.09 / 140) > 1e-9)
                throw new IllegalStateException("Automatic crafting failed: " + id);
            if (panel.engine().exportSave().resources().get(id).capacity() != -1)
                throw new IllegalStateException("Crafted material was capped: " + id);
        }
        if (!panel.saveGame()) throw new IllegalStateException("Crafting save failed");
        var saved = panel.engine().exportSave();
        panel.jobButton("craft_Plywood", false).doClick();
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Crafting load failed");
        panel.jobButton("craft_Plywood", false).doClick();
        if (panel.engine().exportSave().civilization().workers().get("craft_Plywood") != 0)
            throw new IllegalStateException("Craftsman button used old session");
        panel.jobButton("craft_Plywood", true).doClick();
        render(panel, Path.of("build/preview-crafting.png"), 1540);
        garrisonSmokeTest(panel);
    }

    private static void garrisonSmokeTest(EvolvePanel panel) throws Exception {
        for (String job : java.util.List.of("craft_Plywood", "craft_Brick", "craft_Wrought_Iron", "cement_worker"))
            panel.jobButton(job, false).doClick();
        buyWithStorage(panel, "tech-garrison");
        buyWithStorage(panel, "city-garrison");
        if (panel.engine().exportSave().resources().get("Furs").capacity() != 100)
            throw new IllegalStateException("Fur storage applied before midLoop");
        panel.engine().advanceTicks(995 - panel.engine().exportSave().tickRemainder());
        if (panel.engine().snapshot().garrison().soldiers() != 0 || panel.engine().snapshot().garrison().trainingProgress() != 99.5)
            throw new IllegalStateException("Soldier training boundary failed");
        if (!panel.saveGame()) throw new IllegalStateException("Training save failed");
        var saved = panel.engine().exportSave();
        panel.engine().advanceTicks(5);
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Loading did not restore training progress");
        panel.engine().advanceTicks(5);
        if (panel.engine().snapshot().garrison().soldiers() != 1 || panel.engine().exportSave().resources().get("Furs").amount() != 0)
            throw new IllegalStateException("Training completed before production");
        panel.engine().advanceTicks(1005);
        panel.refresh();
        if (panel.engine().snapshot().garrison().soldiers() != 2 ||
                Math.abs(panel.engine().exportSave().resources().get("Furs").changePerSecond() - 0.2) > 1e-9)
            throw new IllegalStateException("Garrison hunting failed");
        render(panel, Path.of("build/preview-garrison.png"), 1660);
        librarySmokeTest(panel);
    }

    private static void librarySmokeTest(EvolvePanel panel) throws Exception {
        waitFor(panel, "Stone", 3000);
        panel.jobButton("cement_worker", true).doClick();
        buyWithStorage(panel, "tech-library");
        buyWithStorage(panel, "city-library");
        waitFor(panel, "Knowledge", 1125);
        alignMidLoop(panel);
        panel.actionButton("tech-thesis").doClick();
        if (panel.engine().exportSave().civilization().professorImpact() != 0.5)
            throw new IllegalStateException("Thesis applied before midLoop");
        if (!panel.saveGame()) throw new IllegalStateException("Pending thesis save failed");
        panel.engine().advanceTicks(20);
        panel.loadGame();
        if (panel.engine().exportSave().civilization().professorImpact() != 0.5)
            throw new IllegalStateException("Loading applied pending thesis");
        panel.engine().advanceTicks(25);
        if (Math.abs(panel.engine().exportSave().resources().get("Knowledge").changePerSecond() - 1.51 * 1.05) > 1e-9)
            throw new IllegalStateException("Library knowledge production failed");
        var library = panel.engine().snapshot().actions().stream().filter(a -> a.id().equals("city-library")).findFirst().orElseThrow();
        library.costs().forEach((id, amount) -> {
            if (java.util.List.of("Plywood", "Brick").contains(id)) {
                while (panel.engine().exportSave().resources().get(id).amount() < amount) buyWithStorage(panel, "craft-" + id);
            } else waitFor(panel, id, amount);
        });
        waitFor(panel, "Knowledge", 3240);
        alignMidLoop(panel);
        double capacity = panel.engine().exportSave().resources().get("Knowledge").capacity();
        panel.actionButton("tech-research_grant").doClick();
        panel.actionButton("city-library").doClick();
        if (panel.engine().exportSave().resources().get("Knowledge").capacity() != capacity + 125 ||
                panel.engine().exportSave().civilization().professorImpact() != 0.52)
            throw new IllegalStateException("Library did not retain pending university storage");
        if (!panel.saveGame()) throw new IllegalStateException("Pending research grant save failed");
        var saved = panel.engine().exportSave();
        panel.engine().advanceTicks(20);
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Loading applied pending university storage");
        panel.jobButton("professor", true).doClick();
        panel.jobButton("professor", false).doClick();
        if (panel.engine().exportSave().civilization().professorImpact() != 0.52)
            throw new IllegalStateException("Professor controls discarded thesis impact");
        panel.engine().advanceTicks(25);
        panel.refresh();
        int universities = panel.engine().exportSave().structures().get("university");
        if (Math.abs(panel.engine().exportSave().resources().get("Knowledge").capacity() - (100 + universities * 520d + 250)) > 1e-9)
            throw new IllegalStateException("Research grant capacity failed");
        render(panel, Path.of("build/preview-library.png"), 1660);
        carpentrySmokeTest(panel);
    }

    private static void carpentrySmokeTest(EvolvePanel panel) throws Exception {
        buyWithStorage(panel, "tech-apprentices");
        while (panel.engine().exportSave().resources().get("Knowledge").capacity() < 5200) {
            buyWithStorage(panel, "city-library");
            panel.engine().advanceTicks(20);
        }
        buyWithStorage(panel, "tech-carpentry");
        for (int i = 0; i < 3; i++) panel.jobButton("craft_Plywood", true).doClick();
        if (panel.engine().exportSave().civilization().workers().get("craft_Plywood") != 3)
            throw new IllegalStateException("Apprentices were not assigned");
        waitFor(panel, "Lumber", 110);
        double before = panel.engine().exportSave().resources().get("Plywood").amount();
        panel.actionButton("craft-Plywood").doClick();
        double ratio = 1 + 3 * 0.03 + 2 * 0.03 + panel.engine().exportSave().structures().get("sawmill") * 0.02;
        if (Math.abs(panel.engine().exportSave().resources().get("Plywood").amount() - before - ratio) > 1e-9)
            throw new IllegalStateException("Carpentry manual bonus failed");
        if (!panel.saveGame()) throw new IllegalStateException("Carpentry save failed");
        var saved = panel.engine().exportSave();
        panel.jobButton("craft_Plywood", false).doClick();
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Loading changed crafting upgrades");
        panel.jobButton("craft_Plywood", false).doClick();
        panel.engine().advanceTicks(5);
        panel.refresh();
        if (Math.abs(panel.engine().exportSave().resources().get("Plywood").changePerSecond() - (ratio - 0.03) * 2 / 140) > 1e-9)
            throw new IllegalStateException("Apprentice controls used the previous session");
        render(panel, Path.of("build/preview-carpentry.png"), 1660);
        coalSmokeTest(panel);
    }

    private static void coalSmokeTest(EvolvePanel panel) throws Exception {
        panel.jobButton("craft_Plywood", false).doClick();
        panel.jobButton("craft_Plywood", false).doClick();
        panel.jobButton("cement_worker", false).doClick();
        waitFor(panel, "Knowledge", 4320);
        alignMidLoop(panel);
        panel.actionButton("tech-coal_mining").doClick();
        if (panel.engine().exportSave().resources().get("Coal").capacity() != 50)
            throw new IllegalStateException("Coal shed storage applied before midLoop");
        if (!panel.saveGame()) throw new IllegalStateException("Coal research save failed");
        panel.engine().advanceTicks(20);
        panel.loadGame();
        if (panel.engine().exportSave().resources().get("Coal").capacity() != 50)
            throw new IllegalStateException("Loading applied pending coal storage");
        buyWithStorage(panel, "city-coal_mine");
        panel.jobButton("coal_miner", true).doClick();
        waitFor(panel, "Coal", 1);
        panel.smelterButton("Coal", true).doClick();
        if (panel.engine().exportSave().civilization().smelter().coal() != 1 ||
                panel.engine().exportSave().civilization().smelter().wood() != 0)
            throw new IllegalStateException("Coal fuel did not replace wood");
        panel.engine().advanceTicks(5);
        if (Math.abs(panel.engine().exportSave().resources().get("Coal").changePerSecond() - (1.24 * 0.2 - 0.25)) > 1e-9)
            throw new IllegalStateException("Coal net production failed");
        if (!panel.saveGame()) throw new IllegalStateException("Coal fuel save failed");
        var saved = panel.engine().exportSave();
        panel.smelterButton("Wood", true).doClick();
        panel.loadGame();
        if (!saved.equals(panel.engine().exportSave())) throw new IllegalStateException("Loading changed coal fuel");
        panel.smelterButton("Wood", true).doClick();
        if (panel.engine().exportSave().civilization().smelter().coal() != 0)
            throw new IllegalStateException("Fuel control used the previous session");
        panel.smelterButton("Coal", true).doClick();
        panel.refresh();
        render(panel, Path.of("build/preview-coal.png"), 1720);
    }

    private static void alignMidLoop(EvolvePanel panel) {
        while (panel.engine().exportSave().tickRemainder() != 0 || panel.engine().exportSave().loopCycle() % 4 != 0)
            panel.engine().advanceTicks(1);
        panel.refresh();
    }

    private static void buyWithStorage(EvolvePanel panel, String id) {
        var action = panel.engine().snapshot().actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        for (int expansions = 0; !action.fitsStorage(); expansions++) {
            if (expansions >= 100) throw new IllegalStateException("GUI expansion stalled for " + id);
            var resources = panel.engine().exportSave().resources();
            var blocked = action.costs().entrySet().stream().filter(e -> e.getValue() > resources.get(e.getKey()).capacity()).findFirst().orElseThrow();
            var candidates = switch (blocked.getKey()) {
                case "Money" -> java.util.List.of("city-bank");
                case "Knowledge" -> java.util.List.of("city-university");
                case "Lumber" -> java.util.List.of("city-shed", "city-lumber_yard");
                case "Stone" -> java.util.List.of("city-shed", "city-rock_quarry");
                default -> java.util.List.of("city-shed");
            };
            var actions = panel.engine().snapshot().actions();
            String expansion = candidates.stream().filter(candidate -> !candidate.equals(id)).filter(candidate ->
                    actions.stream().anyMatch(a -> a.id().equals(candidate) && a.fitsStorage())).findFirst().orElse(candidates.getFirst());
            if (expansion.equals(id)) throw new IllegalStateException("No GUI storage expansion for " + id);
            buyWithStorage(panel, expansion);
            action = panel.engine().snapshot().actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        }
        action.costs().forEach((resource, amount) -> {
            for (int attempts = 0; panel.engine().exportSave().resources().get(resource).amount() < amount; attempts++) {
                if (attempts >= 600) throw new IllegalStateException("GUI production stalled for " + resource);
                if (java.util.List.of("Plywood", "Brick", "Wrought_Iron").contains(resource)) buyWithStorage(panel, "craft-" + resource);
                else panel.engine().advanceTicks(200);
            }
        });
        panel.refresh();
        if (!panel.actionButton(id).isEnabled()) throw new IllegalStateException("GUI action unavailable: " + id);
        panel.actionButton(id).doClick();
    }

    private static void buyWhenReady(EvolvePanel panel, String id) {
        var action = panel.engine().snapshot().actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        if (!action.fitsStorage()) throw new IllegalStateException("Insufficient GUI storage for " + id);
        action.costs().forEach((resource, amount) -> waitFor(panel, resource, amount));
        panel.actionButton(id).doClick();
    }

    private static void waitFor(EvolvePanel panel, String id, double amount) {
        for (int ticks = 0; ticks <= 60000; ticks += 5) {
            if (panel.engine().exportSave().resources().get(id).amount() >= amount) { panel.refresh(); return; }
            panel.engine().advanceTicks(5);
        }
        throw new IllegalStateException("GUI production did not reach " + id + " " + amount);
    }

    private static void render(EvolvePanel panel, Path output, int height) throws Exception {
        for (String locale : java.util.List.of("en_us", "zh_cn")) {
            var localizer = new Localizer(locale);
            var snapshot = panel.engine().snapshot();
            var texts = new java.util.ArrayList<io.github.evolve.core.TextRef>();
            snapshot.resources().forEach(resource -> texts.add(resource.name()));
            snapshot.jobs().forEach(job -> texts.add(job.name()));
            snapshot.actions().forEach(action -> { texts.add(action.title()); texts.addAll(action.tooltip()); });
            for (var text : texts) if (localizer.text(text).startsWith("evolve."))
                throw new IllegalStateException("Missing " + locale + " translation: " + text.key());
        }
        panel.setSize(1120, height);
        layoutTree(panel);
        var image = new BufferedImage(1120, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        panel.printAll(graphics);
        graphics.dispose();
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
        System.out.println("GUI smoke test passed; rendered " + output.toAbsolutePath());
    }

    private static void layoutTree(Container component) {
        component.doLayout();
        for (var child : component.getComponents()) if (child instanceof Container container) layoutTree(container);
    }
}

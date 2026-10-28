package io.github.evolve.desktop;

import io.github.evolve.core.EvolutionEngine;
import io.github.evolve.core.GameCommand;
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
                    panel.setSize(1120, 760);
                    layoutTree(panel);
                    var image = new BufferedImage(1120, 760, BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics();
                    panel.printAll(graphics);
                    graphics.dispose();
                    var output = Path.of("build/preview.png");
                    Files.createDirectories(output.getParent());
                    ImageIO.write(image, "png", output.toFile());
                    System.out.println("GUI smoke test passed; rendered " + output.toAbsolutePath());
                } catch (Exception e) { throw new IllegalStateException("GUI smoke test failed", e); }
            });
        } finally {
            Files.deleteIfExists(directory.resolve("game.json.bak"));
            Files.deleteIfExists(directory.resolve("game.json"));
            Files.deleteIfExists(directory);
        }
    }

    private static void layoutTree(Container component) {
        component.doLayout();
        for (var child : component.getComponents()) if (child instanceof Container container) layoutTree(container);
    }
}

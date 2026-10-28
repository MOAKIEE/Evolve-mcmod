package io.github.evolve.desktop;

import io.github.evolve.core.CommandResult;
import io.github.evolve.core.EvolutionEngine;
import io.github.evolve.core.GameCommand;
import io.github.evolve.core.GameEngine;
import io.github.evolve.core.GameSnapshot;
import io.github.evolve.core.SaveStore;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The preview renders snapshots only; every resource mutation goes through GameCommand. */
public final class EvolvePanel extends JPanel {
    private static final Color BACKGROUND = new Color(0x101720);
    private static final Color SURFACE = new Color(0x192432);
    private static final Color BUTTON = new Color(0x26394B);
    private static final Color ACCENT = new Color(0x65D9BE);
    private static final Color TEXT = new Color(0xE2ECF4);
    private static final Color MUTED = new Color(0xA3B7C9);
    private static final Font BODY = new Font("Dialog", Font.PLAIN, 14);
    private GameEngine engine;
    private final SaveStore store;
    private Localizer localizer;
    private final JLabel title = label(28, TEXT);
    private final JLabel subtitle = label(14, MUTED);
    private final JLabel resourceTitle = label(17, ACCENT);
    private final JLabel actionTitle = label(17, ACCENT);
    private final JLabel elapsed = label(12, MUTED);
    private final JLabel status = label(12, MUTED);
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JPanel actionGrid = panel(new GridLayout(0, 2, 10, 10));
    private final JPanel resourceList = panel(null);
    private final Map<String, JButton> actionButtons = new LinkedHashMap<>();
    private final Map<String, ResourceCard> resourceCards = new LinkedHashMap<>();
    private final JTextArea handoff = new JTextArea();
    private final JPanel jobs = panel(new GridLayout(0, 1, 0, 6));
    private final JLabel jobTitle = label(17, ACCENT);
    private final Map<String, JobRow> jobRows = new LinkedHashMap<>();
    private final JButton pause = button();
    private final JButton save = button();
    private final JButton load = button();
    private final JButton fresh = button();
    private List<String> previousActionIds = List.of();
    private List<String> previousResourceIds;
    private List<String> previousJobIds = List.of();
    private long savedRevision = -1;

    public EvolvePanel(GameEngine engine, SaveStore store, String locale) {
        this.engine = engine;
        this.store = store;
        this.localizer = new Localizer(locale);
        setLayout(new BorderLayout(16, 16));
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));

        var header = panel(new BorderLayout(8, 8));
        var heading = panel(new GridLayout(2, 1, 0, 4));
        heading.add(title);
        heading.add(subtitle);
        header.add(heading, BorderLayout.WEST);
        var controls = panel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        var languages = new JComboBox<>(new String[] { "简体中文", "English" });
        languages.setFont(BODY);
        languages.setSelectedIndex(locale.equals("zh_cn") ? 0 : 1);
        languages.addActionListener(event -> {
            localizer = new Localizer(languages.getSelectedIndex() == 0 ? "zh_cn" : "en_us");
            refresh();
        });
        controls.add(languages);
        controls.add(pause);
        header.add(controls, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        var content = panel(new BorderLayout(18, 0));
        var left = panel(new BorderLayout(0, 16));
        left.setPreferredSize(new Dimension(260, 400));
        left.add(resourceTitle, BorderLayout.NORTH);
        resourceList.setLayout(new BoxLayout(resourceList, BoxLayout.Y_AXIS));
        handoff.setEditable(false);
        handoff.setLineWrap(true);
        handoff.setWrapStyleWord(true);
        handoff.setFont(BODY);
        handoff.setForeground(TEXT);
        handoff.setBackground(BACKGROUND);
        handoff.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        handoff.setAlignmentX(Component.LEFT_ALIGNMENT);
        handoff.setMaximumSize(new Dimension(400, 100));
        var resourceScroll = new JScrollPane(resourceList, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        resourceScroll.setBorder(null);
        resourceScroll.getViewport().setBackground(BACKGROUND);
        resourceScroll.getVerticalScrollBar().setUnitIncrement(18);
        left.add(resourceScroll, BorderLayout.CENTER);
        content.add(left, BorderLayout.WEST);

        var right = panel(new BorderLayout(0, 14));
        right.add(actionTitle, BorderLayout.NORTH);
        var gridWrapper = panel(new BorderLayout());
        gridWrapper.add(actionGrid, BorderLayout.NORTH);
        var scroll = new JScrollPane(gridWrapper);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        right.add(scroll, BorderLayout.CENTER);
        right.add(jobs, BorderLayout.SOUTH);
        content.add(right, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);

        var footer = panel(new BorderLayout(0, 12));
        progress.setStringPainted(true);
        progress.setForeground(ACCENT);
        progress.setBackground(SURFACE);
        progress.setBorderPainted(false);
        progress.setFont(BODY);
        progress.setPreferredSize(new Dimension(500, 25));
        footer.add(progress, BorderLayout.NORTH);
        var buttons = panel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(elapsed);
        buttons.add(fresh);
        buttons.add(load);
        buttons.add(save);
        footer.add(buttons, BorderLayout.EAST);
        footer.add(status, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        pause.addActionListener(event -> { engine().execute(new GameCommand.Pause(!engine().snapshot().paused())); refresh(); });
        save.addActionListener(event -> saveGame());
        load.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(this, localizer.text("ui_confirm_load"), localizer.text("ui_confirm_title"),
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) loadGame();
        });
        fresh.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(this, localizer.text("ui_confirm_new"), localizer.text("ui_confirm_title"),
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                this.engine = EvolutionEngine.newGame((int) (System.nanoTime() & 0x7fffffff));
                savedRevision = -1;
                status.setText(localizer.text("ui_status"));
                refresh();
            }
        });
        refresh();
    }

    GameEngine engine() { return engine; }
    String uiText(String key) { return localizer.text(key); }
    JButton actionButton(String id) { return actionButtons.get(id); }
    JButton jobButton(String id, boolean recruit) { return recruit ? jobRows.get(id).add : jobRows.get(id).remove; }
    void tick() { engine.advanceTicks(1); refresh(); }
    boolean dirty() { return engine.snapshot().revision() != savedRevision; }

    boolean saveGame() {
        try {
            var snapshot = engine.exportSave();
            store.save(snapshot);
            savedRevision = snapshot.revision();
            status.setText(localizer.text("ui_saved"));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            status.setText(localizer.text("ui_error", e.getMessage()));
            return false;
        }
    }

    void loadGame() {
        try {
            var saved = store.load();
            if (saved.isEmpty()) { status.setText(localizer.text("ui_save_missing")); return; }
            // Validate and create the replacement before changing the live game.
            var replacement = EvolutionEngine.restore(saved.orElseThrow());
            engine = replacement;
            savedRevision = replacement.snapshot().revision();
            status.setText(localizer.text("ui_loaded"));
            refresh();
        } catch (IOException | IllegalArgumentException e) {
            status.setText(localizer.text("ui_error", e.getMessage()));
        }
    }

    void refresh() {
        GameSnapshot snapshot = engine.snapshot();
        title.setText(localizer.text("ui_title"));
        subtitle.setText(localizer.text("ui_subtitle"));
        resourceTitle.setText(localizer.text("ui_resources"));
        actionTitle.setText(localizer.text(snapshot.phase() == GameSnapshot.Phase.EVOLUTION ? "ui_actions" : "ui_civilization_actions"));
        pause.setText(localizer.text(snapshot.paused() ? "ui_resume" : "ui_pause"));
        save.setText(localizer.text("ui_save"));
        load.setText(localizer.text("ui_load"));
        fresh.setText(localizer.text("ui_new"));
        elapsed.setText(localizer.text("ui_time", number(snapshot.elapsedTicks() / 20d)));
        progress.setValue(snapshot.progress());
        progress.setString(localizer.text(snapshot.phase() == GameSnapshot.Phase.PRIMITIVE ? "ui_primitive" : "ui_progress", snapshot.progress()));
        var visibleResources = snapshot.resources().stream().filter(GameSnapshot.ResourceView::visible)
                .sorted(java.util.Comparator.comparingInt(r -> r.id().equals(snapshot.species()) ? 0 : 1)).toList();
        List<String> resourceIds = visibleResources.stream().map(GameSnapshot.ResourceView::id).toList();
        if (!resourceIds.equals(previousResourceIds)) {
            resourceList.removeAll();
            resourceCards.clear();
            for (String id : resourceIds) {
                var card = new ResourceCard();
                resourceCards.put(id, card);
                resourceList.add(card.panel);
                resourceList.add(Box.createVerticalStrut(12));
            }
            resourceList.add(handoff);
            resourceList.add(Box.createVerticalGlue());
            previousResourceIds = resourceIds;
        }
        for (var resource : visibleResources) resourceCards.get(resource.id()).update(resource);
        boolean complete = snapshot.phase() == GameSnapshot.Phase.CIVILIZATION_READY;
        boolean canStart = snapshot.actions().stream().anyMatch(a -> a.id().equals("civilization-start"));
        handoff.setVisible(complete);
        handoff.setText(complete ? localizer.text(canStart ? "ui_ready_supported" : "ui_ready", localizer.text("race_" + snapshot.species()),
                localizer.text("genelab_genus_" + snapshot.genus())) : "");
        jobs.setVisible(!snapshot.jobs().isEmpty());
        jobTitle.setText(localizer.text("ui_jobs"));
        var jobIds = snapshot.jobs().stream().map(GameSnapshot.JobView::id).toList();
        if (!jobIds.equals(previousJobIds)) {
            jobs.removeAll();
            jobRows.clear();
            jobs.add(jobTitle);
            for (var job : snapshot.jobs()) {
                var row = new JobRow(job);
                jobRows.put(job.id(), row);
                jobs.add(row.panel);
            }
            previousJobIds = jobIds;
        }
        int available = snapshot.jobs().stream().filter(GameSnapshot.JobView::defaultJob).mapToInt(GameSnapshot.JobView::workers).sum();
        for (var job : snapshot.jobs()) jobRows.get(job.id()).update(job, available, snapshot.paused());

        List<String> ids = snapshot.actions().stream().map(GameSnapshot.ActionView::id).toList();
        if (!ids.equals(previousActionIds)) {
            actionGrid.removeAll();
            actionButtons.clear();
            for (String id : ids) {
                var button = button();
                button.setHorizontalAlignment(SwingConstants.LEFT);
                button.setPreferredSize(new Dimension(270, 86));
                button.addActionListener(event -> act(id, 1));
                actionButtons.put(id, button);
                actionGrid.add(button);
            }
            previousActionIds = ids;
            actionGrid.revalidate();
        }
        for (var action : snapshot.actions()) {
            var button = actionButtons.get(action.id());
            String costs = action.costs().entrySet().stream().map(entry -> localizer.text(snapshot.resources().stream()
                    .filter(r -> r.id().equals(entry.getKey())).findFirst().orElseThrow().name()) + " " + number(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining("  ·  "));
            String count = action.count() > 0 ? "  ×" + action.count() : "";
            button.setText("<html><div style='width:190px'><b>" + html(localizer.text(action.title()) + count) +
                    "</b><br><span style='font-size:10px'>" + html(costs.isEmpty() ? localizer.text("ui_no_cost") : costs) + "</span></div></html>");
            button.setEnabled(action.enabled());
            String tooltip = action.tooltip().stream().map(localizer::text).map(EvolvePanel::html)
                    .collect(java.util.stream.Collectors.joining("<br><br>"));
            if (!action.fitsStorage()) tooltip += "<br><br>" + html(localizer.text(lockedResource(snapshot, action) ? "ui_unlock_resource" : "ui_storage"));
            button.setToolTipText("<html><div style='width:300px'>" + tooltip + "</div></html>");
        }
        revalidate();
        repaint();
    }

    CommandResult act(String id, int repetitions) {
        var result = engine.execute(new GameCommand.Action(id, repetitions));
        if (result.status() != CommandResult.Status.OK) status.setText(localizer.text(switch (result.status()) {
            case PAUSED -> "ui_paused";
            case STORAGE_TOO_SMALL -> engine.snapshot().actions().stream().filter(a -> a.id().equals(id))
                    .anyMatch(a -> lockedResource(engine.snapshot(), a)) ? "ui_unlock_resource" : "ui_storage";
            case RESOURCE_FULL -> "ui_full";
            default -> "ui_unaffordable";
        }));
        refresh();
        return result;
    }

    private static boolean lockedResource(GameSnapshot snapshot, GameSnapshot.ActionView action) {
        return snapshot.resources().stream().anyMatch(resource -> !resource.visible() && action.costs().containsKey(resource.id()));
    }

    CommandResult assignJob(String id, int delta) {
        var result = engine.execute(new GameCommand.AssignJob(id, delta));
        if (result.status() != CommandResult.Status.OK) status.setText(localizer.text(
                result.status() == CommandResult.Status.PAUSED ? "ui_paused" : "ui_no_workers"));
        refresh();
        return result;
    }

    private final class JobRow {
        final JPanel panel = panel(new BorderLayout(10, 0));
        final JLabel text = label(14, TEXT);
        final JButton add = button();
        final JButton remove = button();

        JobRow(GameSnapshot.JobView job) {
            panel.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 8));
            panel.setBackground(SURFACE);
            panel.add(text, BorderLayout.CENTER);
            if (!job.defaultJob()) {
                var controls = EvolvePanel.panel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
                controls.setBackground(SURFACE);
                remove.setText("−");
                add.setText("+");
                remove.addActionListener(event -> assignJob(job.id(), -1));
                add.addActionListener(event -> assignJob(job.id(), 1));
                controls.add(remove);
                controls.add(add);
                panel.add(controls, BorderLayout.EAST);
            }
        }

        void update(GameSnapshot.JobView job, int available, boolean paused) {
            text.setText(job.capacity() < 0 ? localizer.text("ui_job_workers", localizer.text(job.name()), job.workers()) :
                    localizer.text("ui_job_workers_limited", localizer.text(job.name()), job.workers(), job.capacity()));
            add.setToolTipText(localizer.text("ui_assign_worker"));
            remove.setToolTipText(localizer.text("ui_release_worker"));
            add.getAccessibleContext().setAccessibleName(localizer.text("ui_assign_worker") + ": " + localizer.text(job.name()));
            remove.getAccessibleContext().setAccessibleName(localizer.text("ui_release_worker") + ": " + localizer.text(job.name()));
            add.setEnabled(!paused && available > 0 && (job.capacity() == -1 || job.workers() < job.capacity()));
            remove.setEnabled(!paused && job.workers() > 0);
        }
    }

    private final class ResourceCard {
        final JPanel panel = panel(new BorderLayout(0, 10));
        final JLabel name = label(17, TEXT);
        final JLabel rate = label(12, MUTED);
        final JProgressBar amount = new JProgressBar(0, 1000);
        ResourceCard() {
            panel.setBackground(SURFACE);
            panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
            panel.setMaximumSize(new Dimension(400, 106));
            panel.setAlignmentX(Component.LEFT_ALIGNMENT);
            var row = panel(new BorderLayout());
            row.setBackground(SURFACE);
            row.add(name, BorderLayout.WEST);
            row.add(rate, BorderLayout.EAST);
            panel.add(row, BorderLayout.NORTH);
            amount.setForeground(ACCENT);
            amount.setBackground(BACKGROUND);
            amount.setStringPainted(true);
            amount.setBorderPainted(false);
            amount.setFont(BODY);
            amount.setPreferredSize(new Dimension(220, 24));
            panel.add(amount, BorderLayout.CENTER);
        }
        void update(GameSnapshot.ResourceView resource) {
            panel.setVisible(resource.visible());
            name.setText(localizer.text(resource.name()));
            rate.setText((resource.changePerSecond() >= 0 ? "+" : "") + number(resource.changePerSecond()) + " /s");
            amount.setValue((int) (resource.amount() / resource.capacity() * 1000));
            amount.setString(number(resource.amount()) + " / " + number(resource.capacity()));
        }
    }

    private static JPanel panel(java.awt.LayoutManager layout) {
        var panel = new JPanel(layout);
        panel.setBackground(BACKGROUND);
        return panel;
    }
    private static JLabel label(int size, Color color) {
        var label = new JLabel();
        label.setFont(BODY.deriveFont((float) size));
        label.setForeground(color);
        return label;
    }
    private static JButton button() {
        var button = new JButton();
        button.setFont(BODY);
        button.setForeground(TEXT);
        button.setBackground(BUTTON);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x385168)),
                BorderFactory.createEmptyBorder(9, 12, 9, 12)));
        return button;
    }
    private static String number(double value) { return new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value); }
    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
}

package app;

import model.Instrument;
import reader.Preprocessor;
import reader.midi.MidiReader;
import tab.Parser;
import tab.StyleSettings;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Bounds;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import javax.sound.midi.InvalidMidiDataException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The tab of one column with a block per measure, laid out in boxes like the finished tab. Clicking a measure starts a box there or joins it to the box before,
// and right-clicking plays the file from it
final class BoxEditor {
    private static final double MIN_PREVIEW_WIDTH = 440;
    private static final double MAX_PREVIEW_WIDTH = 660;
    private final MidiReader midi;
    private final int measuresPerRow;
    private final String sharpStyle;
    private final boolean mergeStaves;
    private final MeasurePlayer player;

    final VBox view;
    // Measures (counted from 0) where a new box starts, after the first
    final TreeSet<Integer> starts = new TreeSet<>();
    private final Label summary = new Label();
    private final ChoiceBox<String> columnChoice = new ChoiceBox<>();
    private final Button playButton = new Button();
    private final GridPane grid = new GridPane();
    private final ScrollPane scroll = new ScrollPane(grid);
    private final Label placeholder = new Label();
    private final StackPane previewArea;
    // Fits the window to the preview, once its width is known
    private final Runnable fitWindow;
    private boolean fitted;

    private List<Long> measureStarts = List.of();
    private int measureCount;
    private boolean pickup;
    private List<PreviewColumn> columns = List.of();
    private final List<MeasureCell> cells = new ArrayList<>();
    // Counts previews started, so one that finishes after a newer one started is ignored
    private volatile int previewNumber;
    // Previews are made one at a time, so changing several instruments quickly doesn't make many at once
    private final ExecutorService previewThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Box preview");
        thread.setDaemon(true);
        return thread;
    });
    private MeasureCell playingCell;
    // Follows the file while it plays, marking the measure it's on
    private final AnimationTimer playhead = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (player.isStarting()) {
                // Still at the old position
                return;
            }
            if (player.isPlaying()) {
                long tick = player.getTick();
                int measure = measureAt(tick);
                showPlaying(measure);
                if (playingCell != null) {
                    playingCell.showPlayingAt((tick - measureStarts.get(measure)) / (double) midi.getSequence().getResolution());
                }
            } else {
                stopped();
            }
        }
    };

    BoxEditor(MidiReader midi, int measuresPerRow, String sharpStyle, boolean mergeStaves, Runnable fitWindow) {
        this.fitWindow = fitWindow;
        this.midi = midi;
        this.measuresPerRow = measuresPerRow;
        this.sharpStyle = sharpStyle;
        this.mergeStaves = mergeStaves;
        this.player = new MeasurePlayer(midi.getSequence());

        Label heading = Gui.heading("Boxes");
        summary.getStyleClass().add("field-label");
        columnChoice.setTooltip(new Tooltip("The column shown"));
        columnChoice.setOnAction(e -> showColumn());
        playButton.getStyleClass().add("secondary");
        playButton.setOnAction(e -> {
            if (player.isPlaying()) {
                player.stop();
                stopped();
            } else {
                play(0);
            }
        });
        stopped();
        Button reset = new Button("Reset");
        reset.getStyleClass().add("secondary");
        reset.setTooltip(new Tooltip("Go back to one box for the whole piece"));
        reset.setOnAction(e -> {
            starts.clear();
            layoutCells();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        for (Control control : List.of(columnChoice, playButton, reset)) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox headingRow = new HBox(8, new VBox(2, heading, summary), spacer, columnChoice, playButton, reset);
        headingRow.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("Click a measure to start a new box there, or a box's first measure to join it to the box "
                + "before. Right-click a measure to listen from there.");
        hint.getStyleClass().add("field-label");
        hint.setWrapText(true);

        grid.setHgap(2);
        grid.setVgap(2);
        grid.getStyleClass().add("box-grid");
        // The letter column, then the measures sharing the rest of the width
        grid.getColumnConstraints().add(new ColumnConstraints());
        for (int i = 0; i < measuresPerRow; i++) {
            // The same width for each measure column, whatever its measures hold
            ColumnConstraints measureColumn = new ColumnConstraints(60, 100, Double.MAX_VALUE);
            measureColumn.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(measureColumn);
        }
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("plain-scroll");
        placeholder.getStyleClass().add("field-label");
        previewArea = new StackPane(scroll, placeholder);
        previewArea.getStyleClass().add("box-preview");
        previewArea.setPrefSize(MIN_PREVIEW_WIDTH, 394);
        VBox.setVgrow(previewArea, Priority.ALWAYS);
        // Wraps to the preview's width instead of widening the window
        hint.prefWidthProperty().bind(previewArea.widthProperty());
        hint.setMinWidth(0);
        view = new VBox(10, headingRow, previewArea, hint);
    }

    // Shows the tab of the included tracks, keeping the boxes placed so far
    void tracksChanged(List<Boolean> included) {
        player.stop();
        // Only the chosen tracks are played
        try {
            player.setSequence(midi.getPlaybackSequence(included));
        } catch (InvalidMidiDataException e) {
            player.setSequence(midi.getSequence());
        }
        MidiReader.MeasurePlan plan = midi.getMeasurePlan(included);
        measureCount = plan.getMeasureCount();
        pickup = plan.hasPickup();
        measureStarts = plan.getMeasureStarts();
        starts.removeIf(start -> start >= measureCount);
        readPreview(included);
    }

    // Stops playing, once the window closes
    void close() {
        playhead.stop();
        player.close();
        previewThread.shutdownNow();
    }

    // ===== Preview =====

    // A column of the tab: its tab for each measure
    private static final class PreviewColumn {
        final String name;
        final List<String> tabs = new ArrayList<>();
        final List<List<Parser.ChordSpan>> chords = new ArrayList<>();

        PreviewColumn(String name) {
            this.name = name;
        }
    }

    // Converts the chosen tracks in the background, with a line per measure, and shows them when done
    private void readPreview(List<Boolean> included) {
        int number = ++previewNumber;
        showPlaceholder("Loading the tab…");
        Task<List<PreviewColumn>> task = new Task<>() {
            @Override
            protected List<PreviewColumn> call() throws Exception {
                // A newer preview was asked for while this one waited its turn
                if (number != previewNumber) {
                    return null;
                }
                return readColumns(included);
            }
        };
        task.setOnSucceeded(e -> {
            if (number == previewNumber) {
                columns = task.getValue();
                String shown = columnChoice.getValue();
                columnChoice.getItems().setAll(columns.stream().map(column -> column.name).toList());
                columnChoice.setValue(columnChoice.getItems().contains(shown) ? shown : columnChoice.getItems().get(0));
                columnChoice.setVisible(columns.size() > 1);
                columnChoice.setManaged(columns.size() > 1);
                showColumn();
                fitPreview();
            }
        });
        task.setOnFailed(e -> {
            if (number == previewNumber) {
                columns = List.of();
                showPlaceholder("The tab couldn't be shown: " + task.getException().getMessage());
            }
        });
        previewThread.execute(task);
    }

    private List<PreviewColumn> readColumns(List<Boolean> included) throws Exception {
        Preprocessor preprocessor = new Preprocessor(midi.toScore(included, List.of()), midi.getTitle(), 1, mergeStaves, true);
        List<Instrument> processed = preprocessor.getProcessedInstruments();
        Parser parser = new Parser(preprocessor.getProcessedInstruments(), preprocessor.getBoxes(), new StyleSettings(),
                sharpStyle, 1, preprocessor.getTitle());
        Map<String, Integer> nameCounts = new HashMap<>();
        processed.forEach(instrument -> nameCounts.merge(instrument.getName(), 1, Integer::sum));
        Map<String, Integer> numbers = new HashMap<>();
        List<PreviewColumn> previewColumns = new ArrayList<>();
        for (Instrument instrument : processed) {
            // Numbered like the table header when names repeat
            int number = numbers.merge(instrument.getName(), 1, Integer::sum);
            PreviewColumn column = new PreviewColumn(nameCounts.get(instrument.getName()) > 1
                    ? instrument.getName() + " " + number : instrument.getName());
            column.tabs.addAll(parser.getLines(instrument));
            column.chords.addAll(parser.getLineChords(instrument));
            previewColumns.add(column);
        }
        return previewColumns;
    }

    // The first time the tab is shown, the preview is made as wide as its rows (within limits), so there's
    // no empty space beside them and wide rows don't need scrolling sideways
    private void fitPreview() {
        if (fitted) {
            return;
        }
        fitted = true;
        Platform.runLater(() -> {
            Insets padding = previewArea.getInsets();
            double scrollBar = 16;
            // As wide as a row of the widest measures, which wrap when the preview is narrower
            double widestMeasure = cells.stream().mapToDouble(cell -> cell.prefWidth(-1)).max().orElse(0);
            double width = grid.prefWidth(-1) - measuresPerRow * 100 + measuresPerRow * (widestMeasure + grid.getHgap())
                    + padding.getLeft() + padding.getRight() + scrollBar;
            previewArea.setPrefWidth(Math.max(MIN_PREVIEW_WIDTH, Math.min(MAX_PREVIEW_WIDTH, Math.ceil(width))));
            fitWindow.run();
        });
    }

    private void showPlaceholder(String text) {
        placeholder.setText(text);
        placeholder.setVisible(true);
        scroll.setVisible(false);
    }

    // Makes a block for each measure of the chosen column
    private void showColumn() {
        PreviewColumn column = columns.stream().filter(c -> c.name.equals(columnChoice.getValue())).findFirst().orElse(null);
        if (column == null) {
            return;
        }
        playingCell = null;
        cells.clear();
        for (int m = 0; m < measureCount; m++) {
            boolean hasLine = m < column.tabs.size();
            cells.add(new MeasureCell(m, hasLine ? column.tabs.get(m) : "", hasLine ? column.chords.get(m) : List.of()));
        }
        layoutCells();
        placeholder.setVisible(false);
        scroll.setVisible(true);
    }

    // Lays out the measures in rows of measuresPerRow, starting a new box where one starts. Each box is a band
    // with its letter on the left
    private void layoutCells() {
        grid.getChildren().clear();
        int row = 0;
        int box = 0;
        int start = 0;
        while (start < cells.size()) {
            Integer next = starts.higher(start);
            int end = next == null ? cells.size() : Math.min(next, cells.size());
            int lines = (end - start + measuresPerRow - 1) / measuresPerRow;
            if (box > 0) {
                Region gap = new Region();
                gap.setMinHeight(4);
                grid.add(gap, 0, row++);
            }
            Region band = new Region();
            band.getStyleClass().addAll("box-band", box % 2 == 0 ? "box-a" : "box-b");
            grid.add(band, 0, row, measuresPerRow + 1, lines);
            char letter = (char) ('A' + Math.min(box, 25));
            Label letterLabel = new Label(Character.toString(letter));
            letterLabel.getStyleClass().add("box-letter");
            grid.add(letterLabel, 0, row, 1, lines);
            GridPane.setValignment(letterLabel, VPos.TOP);
            GridPane.setHalignment(letterLabel, HPos.CENTER);
            for (int m = start; m < end; m++) {
                MeasureCell cell = cells.get(m);
                cell.setBox(letter, m == start);
                grid.add(cell, 1 + (m - start) % measuresPerRow, row + (m - start) / measuresPerRow);
            }
            row += lines;
            box++;
            start = end;
        }
        int boxes = starts.size() + 1;
        summary.setText(boxes + (boxes == 1 ? " box" : " boxes"));
    }

    // A measure's block: its number, with its tab under it
    private final class MeasureCell extends VBox {
        private final int measure;
        private final Label number;
        private final TabLine tabLine;

        MeasureCell(int measure, String tab, List<Parser.ChordSpan> chords) {
            this.measure = measure;
            getStyleClass().add("measure-cell");
            // Measures are numbered like in MuseScore: a pickup is measure 0
            number = new Label(Integer.toString(pickup ? measure : measure + 1));
            number.getStyleClass().add("measure-number");
            tabLine = new TabLine(tab, chords);
            getChildren().addAll(number, tabLine);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.SECONDARY) {
                    play(measure);
                } else if (e.getButton() == MouseButton.PRIMARY && measure > 0) {
                    if (!starts.remove(measure)) {
                        starts.add(measure);
                    }
                    layoutCells();
                }
            });
        }

        void setBox(char letter, boolean boxStart) {
            getStyleClass().remove("box-start");
            if (boxStart) {
                getStyleClass().add("box-start");
            }
            String number = this.number.getText();
            Tooltip.install(this, new Tooltip("Measure " + number + ", box " + letter
                    + (measure == 0 ? "" : boxStart ? "\nClick to join it to the box before" : "\nClick to start a new box here")
                    + "\nRight-click to listen from here"));
        }

        void setPlaying(boolean playing) {
            getStyleClass().remove("playing");
            if (playing) {
                getStyleClass().add("playing");
            } else {
                tabLine.highlight(-1);
            }
        }

        // Highlights the chord playing at a point in the measure, in quarter notes from its start
        void showPlayingAt(double quarters) {
            tabLine.highlight(quarters);
        }
    }

    // A measure's tab as text: bold, underlined (triplets) and italic (grace notes) where it is in the finished tab,
    // and sharps in green. The chord being played gets a highlight behind it
    private static final class TabLine extends StackPane {
        private final TextFlow flow = new TextFlow();
        private final Pane highlights = new Pane();
        private final List<Parser.ChordSpan> chords;
        // The text of each chord
        private final List<List<Text>> chordTexts = new ArrayList<>();
        private int highlighted = -1;

        TabLine(String html, List<Parser.ChordSpan> chords) {
            this.chords = chords;
            chords.forEach(chord -> chordTexts.add(new ArrayList<>()));
            setAlignment(Pos.TOP_LEFT);
            highlights.setMouseTransparent(true);
            highlights.setMinSize(0, 0);
            highlights.setPrefSize(0, 0);
            getChildren().addAll(highlights, flow);
            readHtml(html);
            if (flow.getChildren().isEmpty()) {
                Text rest = new Text("·");
                rest.getStyleClass().addAll("tab-text", "empty");
                flow.getChildren().add(rest);
            }
        }

        private void readHtml(String html) {
            List<String> open = new ArrayList<>();
            StringBuilder run = new StringBuilder();
            int runChord = -1;
            int i = 0;
            while (i < html.length()) {
                char c = html.charAt(i);
                // Text from different chords goes in different runs, so each chord can be highlighted
                int chord = chordAt(i);
                if (chord != runChord) {
                    addRun(run, open, runChord);
                    runChord = chord;
                }
                if (c == '<' || c == '&') {
                    int end = html.indexOf(c == '<' ? '>' : ';', i);
                    if (end == -1) {
                        run.append(c);
                        i++;
                        continue;
                    }
                    String inside = html.substring(i + 1, end);
                    i = end + 1;
                    if (c == '&') {
                        run.append(switch (inside) {
                            case "lt" -> "<";
                            case "gt" -> ">";
                            case "amp" -> "&";
                            case "nbsp" -> " ";
                            default -> "&" + inside + ";";
                        });
                        continue;
                    }
                    addRun(run, open, runChord);
                    if (inside.startsWith("/")) {
                        String name = inside.substring(1).trim();
                        for (int last = open.size() - 1; last >= 0; last--) {
                            if (open.get(last).split(":")[0].equals(name)) {
                                open.remove(last);
                                break;
                            }
                        }
                    } else {
                        String name = inside.split("[\\s/]", 2)[0];
                        // A coloured span is a sharp
                        open.add(name.equals("span") && inside.contains("color") ? "span:sharp" : name);
                    }
                } else {
                    run.append(c);
                    i++;
                }
            }
            addRun(run, open, runChord);
        }

        // The chord written at an index of the HTML, or -1
        private int chordAt(int index) {
            for (int c = 0; c < chords.size(); c++) {
                if (index >= chords.get(c).start && index < chords.get(c).end) {
                    return c;
                }
            }
            return -1;
        }

        private void addRun(StringBuilder run, List<String> open, int chord) {
            if (run.length() == 0) {
                return;
            }
            Text text = new Text(run.toString());
            text.getStyleClass().add("tab-text");
            if (open.contains("b")) {
                text.getStyleClass().add("bold");
            }
            if (open.contains("u")) {
                text.getStyleClass().add("underline");
            }
            if (open.contains("em")) {
                text.getStyleClass().add("italic");
            }
            if (open.contains("span:sharp")) {
                text.getStyleClass().add("sharp");
            }
            flow.getChildren().add(text);
            if (chord >= 0) {
                chordTexts.get(chord).add(text);
            }
            run.setLength(0);
        }

        // Highlights the chord sounding at a point in quarter notes, or none if it's a rest (or negative)
        void highlight(double quarters) {
            int chord = -1;
            for (int c = 0; c < chords.size() && quarters >= 0; c++) {
                if (quarters >= chords.get(c).position && quarters < chords.get(c).endPosition) {
                    chord = c;
                    break;
                }
            }
            if (chord == highlighted) {
                return;
            }
            highlighted = chord;
            highlights.getChildren().clear();
            if (chord < 0) {
                return;
            }
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (Text text : chordTexts.get(chord)) {
                Bounds bounds = text.getBoundsInParent();
                minX = Math.min(minX, bounds.getMinX());
                minY = Math.min(minY, bounds.getMinY());
                maxX = Math.max(maxX, bounds.getMaxX());
                maxY = Math.max(maxY, bounds.getMaxY());
            }
            if (maxX > minX) {
                Rectangle box = new Rectangle(minX - 2, minY, maxX - minX + 4, maxY - minY);
                box.setArcWidth(6);
                box.setArcHeight(6);
                box.getStyleClass().add("chord-highlight");
                highlights.getChildren().add(box);
            }
        }
    }

    // ===== Playing =====

    private void play(int measure) {
        if (measure >= measureStarts.size()) {
            return;
        }
        player.play(measureStarts.get(measure), error -> Platform.runLater(() -> {
            stopped();
            Dialogs.showError("Could not play the file", error);
        }));
        playButton.setText("■  Stop");
        playButton.setTooltip(new Tooltip("Stop playing"));
        showPlaying(measure);
        playhead.start();
    }

    private void stopped() {
        playhead.stop();
        showPlaying(-1);
        playButton.setText("▶  Play");
        playButton.setTooltip(new Tooltip("Listen from the start. Right-click a measure to listen from there"));
    }

    // The measure a tick is in
    private int measureAt(long tick) {
        int index = Collections.binarySearch(measureStarts, tick);
        return index >= 0 ? index : -index - 2;
    }

    // Marks the measure being played, and scrolls to it if it's out of sight
    private void showPlaying(int measure) {
        MeasureCell cell = measure >= 0 && measure < cells.size() ? cells.get(measure) : null;
        if (cell == playingCell) {
            return;
        }
        if (playingCell != null) {
            playingCell.setPlaying(false);
        }
        playingCell = cell;
        if (cell == null) {
            return;
        }
        cell.setPlaying(true);
        Bounds bounds = cell.getBoundsInParent();
        double contentHeight = grid.getHeight();
        double viewHeight = scroll.getViewportBounds().getHeight();
        if (contentHeight <= viewHeight) {
            return;
        }
        double top = scroll.getVvalue() * (contentHeight - viewHeight);
        if (bounds.getMinY() < top || bounds.getMaxY() > top + viewHeight) {
            scroll.setVvalue(Math.max(0, Math.min(1, (bounds.getMinY() - viewHeight / 4) / (contentHeight - viewHeight))));
        }
    }
}

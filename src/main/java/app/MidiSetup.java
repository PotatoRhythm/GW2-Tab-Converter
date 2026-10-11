package app;

import reader.midi.MidiReader;
import tab.InstrumentKeys;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Asks how a MIDI file's tab is split into boxes. With manual boxes, a window then shows the tab to place them on,
// with the tracks on the left to leave some out
final class MidiSetup {
    // Tracks shown before the list scrolls
    private static final int VISIBLE_TRACKS = 8;

    // The user's choices for manual boxes
    static final class Choices {
        // Whether each track is converted, in order
        final List<Boolean> included;
        // Measures (counted from 0) where a new box starts, after the first
        final List<Integer> boxStarts;

        Choices(List<Boolean> included, List<Integer> boxStarts) {
            this.included = included;
            this.boxStarts = boxStarts;
        }
    }

    private MidiSetup() {
    }

    // How the tab is split into boxes: one box, the suggested boxes, or boxes the user places. The icons are drawn
    // on a 24 x 24 grid: a single box, sparkles, and a mouse pointer
    enum BoxMode {
        NONE("None", "One box for the whole piece.", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z"),
        AUTO("Auto", "Detect the boxes automatically.",
                "M10 3l1.6 4.4L16 9l-4.4 1.6L10 15l-1.6-4.4L4 9l4.4-1.6z M18 13l0.8 2.2L21 16l-2.2 0.8L18 19l-0.8-2.2L15 16l2.2-0.8z"),
        MANUAL("Manual", "Place the boxes yourself.", "M5 3l13 6.5-5.5 1.5-1.5 5.5z M12.5 11l6 6");

        final String label;
        final String description;
        final String icon;

        BoxMode(String label, String description, String icon) {
            this.label = label;
            this.description = description;
            this.icon = icon;
        }
    }

    // Asks how the tab should be split into boxes, with a card for each choice. The last choice has the focus,
    // so Enter picks it again. Empty if the window is closed
    static Optional<BoxMode> askBoxMode(Stage owner, BoxMode last) {
        Stage stage = new Stage();
        stage.setTitle("Boxes");
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);

        Label question = new Label("How should boxes be placed?");
        question.getStyleClass().add("popup-title");
        question.setWrapText(true);

        BoxMode[] result = {null};
        VBox cards = new VBox(8);
        Button lastCard = null;
        for (BoxMode mode : BoxMode.values()) {
            Button card = modeCard(mode);
            card.setOnAction(e -> {
                result[0] = mode;
                stage.close();
            });
            cards.getChildren().add(card);
            if (mode == last) {
                lastCard = card;
            }
        }

        VBox root = new VBox(16, question, cards);
        root.getStyleClass().add("compact");
        root.setPadding(new Insets(18, 20, 18, 20));
        root.setPrefWidth(310);
        Scene scene = new Scene(root);
        // Closing the window (or Esc) cancels
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        Theme.apply(stage, scene);
        stage.setResizable(false);
        Theme.placeOverOwner(stage);
        Button focused = lastCard;
        stage.setOnShown(e -> {
            if (focused != null) {
                focused.requestFocus();
            }
        });
        stage.showAndWait();
        return Optional.ofNullable(result[0]);
    }

    // A choice as a large button: its icon, then its name with what it does under it
    private static Button modeCard(BoxMode mode) {
        SVGPath icon = new SVGPath();
        icon.setContent(mode.icon);
        icon.setStrokeLineCap(StrokeLineCap.ROUND);
        icon.setStrokeLineJoin(StrokeLineJoin.ROUND);
        icon.getStyleClass().add("mode-icon");
        StackPane iconHolder = new StackPane(new Group(icon));
        iconHolder.getStyleClass().add("mode-icon-holder");
        iconHolder.setMinSize(36, 36);
        iconHolder.setMaxSize(36, 36);

        Label name = new Label(mode.label);
        name.getStyleClass().add("mode-name");
        Label description = new Label(mode.description);
        description.getStyleClass().add("mode-description");
        VBox text = new VBox(1, name, description);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox content = new HBox(12, iconHolder, text);
        content.setAlignment(Pos.CENTER_LEFT);
        Button card = new Button();
        card.setGraphic(content);
        card.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("mode-card");
        content.prefWidthProperty().bind(card.widthProperty().subtract(30));
        return card;
    }

    // The window for placing boxes by hand: the tracks on the left, with a tick box to leave each out, and their tab
    // on the right, written with the measures per row, sharp style and staff merging the tab will have.
    // Empty if cancelled
    static Optional<Choices> placeBoxes(Stage owner, String fileName, MidiReader midi, int measuresPerRow,
                                        String sharpStyle, boolean mergeStaves) {
        List<MidiReader.MidiTrack> tracks = midi.getTracks();
        Stage stage = new Stage();
        stage.setTitle("Place Boxes");
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);

        Label title = new Label("Tracks");
        title.getStyleClass().add("popup-title");
        Label file = new Label(fileName);
        file.getStyleClass().add("field-label");

        // With staves merged, the tracks of one part, like a piano's two hands, are one row
        List<List<MidiReader.MidiTrack>> rows = new ArrayList<>();
        for (MidiReader.MidiTrack track : tracks) {
            List<MidiReader.MidiTrack> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (mergeStaves && last != null && track.isSamePartAs(last.get(0))) {
                last.add(track);
            } else {
                rows.add(new ArrayList<>(List.of(track)));
            }
        }

        Button convertButton = new Button("Convert");
        BoxEditor boxEditor = new BoxEditor(midi, measuresPerRow, sharpStyle, mergeStaves, stage::sizeToScene);
        List<TrackCard> cards = new ArrayList<>();
        Runnable tracksChanged = () -> {
            List<Boolean> included = getIncluded(rows, cards);
            boolean any = included.contains(true);
            convertButton.setDisable(!any);
            if (any) {
                boxEditor.tracksChanged(included);
            }
        };
        VBox trackRows = new VBox(8);
        for (List<MidiReader.MidiTrack> row : rows) {
            TrackCard card = new TrackCard(row, tracksChanged);
            cards.add(card);
            trackRows.getChildren().add(card);
        }
        tracksChanged.run();

        // Long lists scroll, with room on the right for the scroll bar only when there's one
        ScrollPane trackList = new ScrollPane(trackRows);
        Runnable fitCards = () -> trackRows.setPadding(new Insets(0,
                trackRows.prefHeight(-1) > trackList.getViewportBounds().getHeight() + 1 ? 14 : 0, 0, 0));
        trackList.viewportBoundsProperty().addListener((value, before, after) -> fitCards.run());
        trackList.setFitToWidth(true);
        trackList.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        trackList.getStyleClass().add("plain-scroll");
        trackList.setPrefViewportHeight(Math.min(rows.size(), VISIBLE_TRACKS) * 70);
        VBox.setVgrow(trackList, Priority.ALWAYS);

        Choices[] result = {null};
        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().add("secondary");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> stage.close());
        convertButton.getStyleClass().add("input-green");
        convertButton.setDefaultButton(true);
        convertButton.setOnAction(e -> {
            result[0] = new Choices(getIncluded(rows, cards), new ArrayList<>(boxEditor.starts));
            stage.close();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttonRow = new HBox(10, spacer, cancelButton, convertButton);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);

        // Tracks on the left, and their tab on the right to place the boxes on
        VBox trackSide = new VBox(16, new VBox(2, title, file), trackList);
        trackSide.setPrefWidth(205);
        trackSide.setMinWidth(190);
        VBox.setVgrow(trackSide, Priority.ALWAYS);
        Separator divider = new Separator(Orientation.VERTICAL);
        divider.getStyleClass().add("column-divider");
        HBox.setHgrow(boxEditor.view, Priority.ALWAYS);
        HBox sides = new HBox(16, trackSide, divider, boxEditor.view);
        VBox.setVgrow(sides, Priority.ALWAYS);
        VBox root = new VBox(16, sides, buttonRow);
        root.getStyleClass().add("compact");
        root.setPadding(new Insets(18, 22, 20, 22));
        Theme.apply(stage, new Scene(root));
        stage.setOnHidden(e -> boxEditor.close());
        Theme.placeOverOwner(stage);
        stage.showAndWait();
        return Optional.ofNullable(result[0]);
    }

    // A track (or the tracks of one part) as a card: its name, MIDI instrument and notes. It's on to convert the
    // track, with a green border, and off to leave it out, faded. Clicking it, or Space or Enter, switches it
    private static final class TrackCard extends VBox {
        private static final PseudoClass ON = PseudoClass.getPseudoClass("on");
        private final BooleanProperty on = new SimpleBooleanProperty(true);

        TrackCard(List<MidiReader.MidiTrack> row, Runnable changed) {
            super(2);
            MidiReader.MidiTrack track = row.get(0);
            List<Integer> pitches = new ArrayList<>();
            row.forEach(rowTrack -> pitches.addAll(rowTrack.getPitches()));
            int lowest = pitches.stream().mapToInt(Integer::intValue).min().orElse(0);
            int highest = pitches.stream().mapToInt(Integer::intValue).max().orElse(0);
            Label name = new Label(track.getName());
            name.getStyleClass().add("track-name");
            name.setWrapText(true);
            Label instrument = new Label(track.getProgramName() + (row.size() > 1 ? ", " + row.size() + " staves" : ""));
            instrument.getStyleClass().add("field-label");
            instrument.setWrapText(true);
            Label notes = new Label(pitches.size() + " notes, " + InstrumentKeys.noteName(lowest) + " to " + InstrumentKeys.noteName(highest));
            notes.getStyleClass().add("track-notes");
            getChildren().addAll(name, instrument, notes);
            getStyleClass().add("track-card");
            setFocusTraversable(true);
            pseudoClassStateChanged(ON, true);
            on.addListener((value, was, isOn) -> {
                pseudoClassStateChanged(ON, isOn);
                changed.run();
            });
            setOnMouseClicked(e -> on.set(!on.get()));
            setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) {
                    on.set(!on.get());
                }
            });
        }

        boolean isOn() {
            return on.get();
        }
    }

    // Whether each track is converted, from the card of its row
    private static List<Boolean> getIncluded(List<List<MidiReader.MidiTrack>> rows, List<TrackCard> cards) {
        List<Boolean> included = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            for (int t = 0; t < rows.get(i).size(); t++) {
                included.add(cards.get(i).isOn());
            }
        }
        return included;
    }
}

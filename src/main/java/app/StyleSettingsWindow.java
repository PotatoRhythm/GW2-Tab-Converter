package app;

import tab.StyleSettings;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

// Style Settings window
final class StyleSettingsWindow {
    private static boolean customColorDialogSimplified;

    // First grid column of the left and right halves
    private static final int LEFT = 0;
    private static final int RIGHT = 3;

    private StyleSettingsWindow() {
    }

    static void show(Stage owner, Preferences preferences) {
        simplifyCustomColorDialog();
        Stage settingsStage = new Stage();
        settingsStage.setTitle("Tab Style");
        settingsStage.initOwner(owner);
        settingsStage.initModality(Modality.APPLICATION_MODAL);

        // ===== Borders =====
        ChoiceBox<String> borderStyleChoice = new ChoiceBox<>();
        borderStyleChoice.getItems().addAll("Standard", "Box");
        CheckBox borderTopToggle = new CheckBox("Top");
        ColorPicker borderTopColor = new ColorPicker();
        CheckBox borderSidesToggle = new CheckBox("Sides");
        ColorPicker borderSidesColor = new ColorPicker();
        CheckBox borderBottomToggle = new CheckBox("Bottom (Box only)");
        ColorPicker borderBottomColor = new ColorPicker();
        CheckBox boxColorToggle = new CheckBox("Box fill");
        ColorPicker boxColorPicker = new ColorPicker();

        // One grid for both halves, so rows and section headings line up across the window:
        // columns 0-1 on the left, a dividing line in column 2, columns 3-4 on the right
        GridPane grid = settingsGrid();
        addHeading(grid, LEFT, 0, "Borders");
        grid.add(new Label("Layout"), LEFT, 1);
        grid.add(widen(borderStyleChoice), LEFT + 1, 1);
        addColorRow(grid, LEFT, 2, borderTopToggle, borderTopColor);
        addColorRow(grid, LEFT, 3, borderSidesToggle, borderSidesColor);
        // The bottom row is only added in the Box layout
        borderBottomToggle.disableProperty().bind(borderStyleChoice.valueProperty().isNotEqualTo("Box"));
        borderBottomColor.disableProperty().bind(
                borderBottomToggle.selectedProperty().not().or(borderBottomToggle.disabledProperty()));
        grid.add(borderBottomToggle, LEFT, 4);
        grid.add(widen(borderBottomColor), LEFT + 1, 4);
        addColorRow(grid, LEFT, 5, boxColorToggle, boxColorPicker);

        // ===== Beat highlights =====
        ChoiceBox<String> measureHighlightChoice = new ChoiceBox<>();
        measureHighlightChoice.getItems().addAll("None", "First Beat", "Strong Beats");
        ColorPicker highlightColor1 = new ColorPicker();
        ColorPicker highlightColor2 = new ColorPicker();
        // First beat colour is used by both highlight modes, the other strong beats only by "Strong Beats"
        highlightColor1.disableProperty().bind(measureHighlightChoice.valueProperty().isEqualTo("None"));
        highlightColor2.disableProperty().bind(measureHighlightChoice.valueProperty().isNotEqualTo("Strong Beats"));

        addHeading(grid, LEFT, 6, "Beat Highlights");
        grid.add(new Label("Highlight"), LEFT, 7);
        grid.add(widen(measureHighlightChoice), LEFT + 1, 7);
        grid.add(new Label("First beat"), LEFT, 8);
        grid.add(widen(highlightColor1), LEFT + 1, 8);
        grid.add(new Label("Other strong beats"), LEFT, 9);
        grid.add(widen(highlightColor2), LEFT + 1, 9);

        // ===== Text colours =====
        CheckBox titleColorToggle = new CheckBox("Title");
        ColorPicker titleColorPicker = new ColorPicker();
        CheckBox instrumentColorToggle = new CheckBox("Instrument names");
        ColorPicker instrumentColorPicker = new ColorPicker();
        CheckBox boxLetterColorToggle = new CheckBox("Box letters");
        ColorPicker boxLetterColorPicker = new ColorPicker();
        CheckBox sharpColorToggle = new CheckBox("Sharps");
        ColorPicker sharpColorPicker = new ColorPicker();
        addHeading(grid, RIGHT, 0, "Text Colours");
        addColorRow(grid, RIGHT, 1, titleColorToggle, titleColorPicker);
        addColorRow(grid, RIGHT, 2, instrumentColorToggle, instrumentColorPicker);
        addColorRow(grid, RIGHT, 3, boxLetterColorToggle, boxLetterColorPicker);
        addColorRow(grid, RIGHT, 4, sharpColorToggle, sharpColorPicker);

        // ===== Closing text =====
        CheckBox closingTextToggle = new CheckBox("Add text below the tab");
        TextArea closingTextField = new TextArea();
        closingTextField.setPromptText("Enter text…");
        // Fills the two rows beside the highlight colours
        closingTextField.setPrefHeight(40);
        // A text box asks for about 40 characters of width by default, which would squeeze the left half
        closingTextField.setPrefWidth(0);
        closingTextField.setMaxHeight(Double.MAX_VALUE);
        closingTextField.setWrapText(true);
        closingTextField.disableProperty().bind(closingTextToggle.selectedProperty().not());
        addHeading(grid, RIGHT, 6, "Closing Text");
        grid.add(closingTextToggle, RIGHT, 7, 2, 1);
        grid.add(closingTextField, RIGHT, 8, 2, 2);

        Separator divider = new Separator(Orientation.VERTICAL);
        divider.getStyleClass().add("column-divider");
        GridPane.setMargin(divider, new Insets(0, 4, 0, 4));
        grid.add(divider, LEFT + 2, 0, 1, 10);

        // Puts a style into the controls
        Consumer<StyleSettings> showStyle = style -> {
            borderStyleChoice.setValue(style.borderStyle);
            borderTopToggle.setSelected(style.borderTopEnabled);
            borderTopColor.setValue(Color.web(style.borderTopColor));
            borderSidesToggle.setSelected(style.borderSidesEnabled);
            borderSidesColor.setValue(Color.web(style.borderSidesColor));
            borderBottomToggle.setSelected(style.borderBottomEnabled);
            borderBottomColor.setValue(Color.web(style.borderBottomColor));
            boxColorToggle.setSelected(style.boxColorEnabled);
            boxColorPicker.setValue(Color.web(style.boxColor));
            closingTextToggle.setSelected(style.closingTextEnabled);
            closingTextField.setText(style.closingText);
            measureHighlightChoice.setValue(style.measureHighlight);
            highlightColor1.setValue(Color.web(style.highlightColor1));
            highlightColor2.setValue(Color.web(style.highlightColor2));
            titleColorToggle.setSelected(style.titleColorEnabled);
            titleColorPicker.setValue(Color.web(style.titleColor));
            instrumentColorToggle.setSelected(style.instrumentColorEnabled);
            instrumentColorPicker.setValue(Color.web(style.instrumentColor));
            boxLetterColorToggle.setSelected(style.boxLetterColorEnabled);
            boxLetterColorPicker.setValue(Color.web(style.boxLetterColor));
            sharpColorToggle.setSelected(style.sharpColorEnabled);
            sharpColorPicker.setValue(Color.web(style.sharpColor));
        };
        showStyle.accept(StyleSettings.load(preferences));
        shareCustomColors(preferences, List.of(borderTopColor, borderSidesColor, borderBottomColor, boxColorPicker,
                highlightColor1, highlightColor2, titleColorPicker, instrumentColorPicker, boxLetterColorPicker,
                sharpColorPicker));

        // ===== Buttons =====
        Button resetButton = new Button("Reset to Defaults");
        resetButton.getStyleClass().add("secondary");
        resetButton.setOnAction(e -> showStyle.accept(new StyleSettings()));
        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().add("secondary");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> settingsStage.close());
        Button saveButton = new Button("Save");
        saveButton.setDefaultButton(true);
        saveButton.getStyleClass().add("input-green");
        saveButton.setOnAction(e -> {
            StyleSettings style = new StyleSettings();
            style.borderStyle = borderStyleChoice.getValue();
            style.borderTopEnabled = borderTopToggle.isSelected();
            style.borderTopColor = toHex(borderTopColor.getValue());
            style.borderSidesEnabled = borderSidesToggle.isSelected();
            style.borderSidesColor = toHex(borderSidesColor.getValue());
            style.borderBottomEnabled = borderBottomToggle.isSelected();
            style.borderBottomColor = toHex(borderBottomColor.getValue());
            style.boxColorEnabled = boxColorToggle.isSelected();
            style.boxColor = toHex(boxColorPicker.getValue());
            style.measureHighlight = measureHighlightChoice.getValue();
            style.highlightColor1 = toHex(highlightColor1.getValue());
            style.highlightColor2 = toHex(highlightColor2.getValue());
            style.titleColorEnabled = titleColorToggle.isSelected();
            style.titleColor = toHex(titleColorPicker.getValue());
            style.instrumentColorEnabled = instrumentColorToggle.isSelected();
            style.instrumentColor = toHex(instrumentColorPicker.getValue());
            style.boxLetterColorEnabled = boxLetterColorToggle.isSelected();
            style.boxLetterColor = toHex(boxLetterColorPicker.getValue());
            style.sharpColorEnabled = sharpColorToggle.isSelected();
            style.sharpColor = toHex(sharpColorPicker.getValue());
            style.closingTextEnabled = closingTextToggle.isSelected();
            style.closingText = closingTextField.getText();
            style.save(preferences);
            settingsStage.close();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttonRow = new HBox(10, resetButton, spacer, cancelButton, saveButton);
        buttonRow.setPadding(new Insets(4, 0, 0, 0));

        // Two halves so the window stays short enough for the screen
        VBox root = new VBox(16, grid, buttonRow);
        root.getStyleClass().add("compact");
        root.setPadding(new Insets(18, 22, 20, 22));
        root.setPrefWidth(620);
        Theme.apply(settingsStage, new Scene(root));
        settingsStage.setResizable(false);
        Theme.placeOverOwner(settingsStage);
        settingsStage.showAndWait();
    }

    // Colours saved with Custom Colour show up in every picker and are remembered for next time
    private static void shareCustomColors(Preferences preferences, List<ColorPicker> pickers) {
        List<Color> saved = new ArrayList<>();
        for (String hex : preferences.get("customColors", "").split(",")) {
            if (!hex.isBlank()) saved.add(Color.web(hex));
        }
        for (ColorPicker picker : pickers) {
            picker.getCustomColors().setAll(saved);
        }
        boolean[] syncing = {false};
        for (ColorPicker picker : pickers) {
            // Copied just after the change, since a list can't be changed while it's reporting a change
            picker.getCustomColors().addListener((ListChangeListener<Color>) change -> {
                if (!syncing[0]) Platform.runLater(() -> {
                    syncing[0] = true;
                    try {
                        // Saving a colour that's already there would add a second swatch
                        List<Color> colors = new ArrayList<>(new LinkedHashSet<>(picker.getCustomColors()));
                        for (ColorPicker each : pickers) {
                            if (!each.getCustomColors().equals(colors)) each.getCustomColors().setAll(colors);
                        }
                        List<String> hexes = new ArrayList<>();
                        for (Color color : colors) {
                            hexes.add(toHex(color));
                        }
                        preferences.put("customColors", String.join(",", hexes));
                    } finally {
                        syncing[0] = false;
                    }
                });
            });
        }
    }

    // Trims the colour pickers' Custom Colour dialog whenever it opens:
    // - the opacity row, since Google Docs ignores opacity (and toHex leaves it out)
    // - the Use button, since Save already applies the colour
    // - "Web" is renamed "Hex", which is what those #RRGGBB codes are usually called
    private static void simplifyCustomColorDialog() {
        if (customColorDialogSimplified) return;
        customColorDialogSimplified = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window.getScene() == null) continue;
                    Node settings = window.getScene().getRoot().lookup("#settings-pane");
                    if (settings instanceof GridPane) {
                        for (Node node : ((GridPane) settings).getChildren()) {
                            // Row 5 holds the opacity label, slider, field and % sign
                            Integer row = GridPane.getRowIndex(node);
                            if (row != null && row == 5) hide(node);
                        }
                    }
                    // The buttons are Save, Use, Cancel
                    Node buttons = window.getScene().getRoot().lookup("#buttons-hbox");
                    if (buttons instanceof HBox && ((HBox) buttons).getChildren().size() == 3) {
                        hide(((HBox) buttons).getChildren().get(1));
                    }
                    renameWebToHex(window.getScene().getRoot());
                }
            }
        });
    }

    // The HSB / RGB / Web switch, and the "Web:" label shown next to the code while Web is picked
    private static void renameWebToHex(Parent root) {
        Node webButton = root.lookup(".right-pill");
        if (!(webButton instanceof ToggleButton)) return;
        String webName = ((ToggleButton) webButton).getText();
        ((ToggleButton) webButton).setText("Hex");
        for (Node node : root.lookupAll(".settings-label")) {
            Label label = (Label) node;
            label.textProperty().addListener((observable, oldText, newText) -> {
                if ((webName + ":").equals(newText)) label.setText("Hex:");
            });
        }
    }

    private static void hide(Node node) {
        node.setVisible(false);
        node.setManaged(false);
    }

    // Rows of a label (or checkbox) on the left and a control filling the right
    private static GridPane settingsGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.getColumnConstraints().addAll(labelColumn(), controlColumn(), new ColumnConstraints(), labelColumn(),
                controlColumn());
        return grid;
    }

    // Only as wide as its longest label, so the controls get the rest
    private static ColumnConstraints labelColumn() {
        ColumnConstraints column = new ColumnConstraints();
        column.setMinWidth(Region.USE_PREF_SIZE);
        return column;
    }

    // The two control columns share the leftover width equally
    private static ColumnConstraints controlColumn() {
        ColumnConstraints column = new ColumnConstraints();
        column.setHgrow(Priority.ALWAYS);
        column.setPrefWidth(0);
        return column;
    }

    // A section heading across a half's label and control columns, with space above it unless it's the first row
    private static void addHeading(GridPane grid, int column, int row, String text) {
        Label heading = Gui.heading(text);
        GridPane.setMargin(heading, new Insets(row > 0 ? 12 : 0, 0, 2, 0));
        grid.add(heading, column, row, 2, 1);
    }

    private static Node widen(Control control) {
        control.setMaxWidth(Double.MAX_VALUE);
        return control;
    }

    // A checkbox with a colour picker that's only usable while it's ticked
    private static void addColorRow(GridPane grid, int column, int row, CheckBox toggle, ColorPicker picker) {
        picker.disableProperty().bind(toggle.selectedProperty().not());
        grid.add(toggle, column, row);
        grid.add(widen(picker), column + 1, row);
    }

    // Converts a Color to #RRGGBB format
    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }
}

package app;

import tab.StyleSettings;

import javafx.geometry.*;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.stage.*;
import javafx.util.StringConverter;

import java.io.File;
import java.util.Map;
import java.util.prefs.Preferences;

public class Gui {
    private final Stage primaryStage;
    private final Preferences preferences;
    private File uploadedFile;
    private boolean converting;

    // User options
    private int measuresPerRow = 2;
    private String sharpStyle = "#";

    // Buttons that change when a file is chosen or a conversion runs
    private final Button fileButton = new Button();
    private final Label fileTitle = new Label();
    private final Label fileHint = new Label();
    private final Button convertButton = new Button();

    public Gui(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.preferences = Preferences.userNodeForPackage(Gui.class);
    }

    public void setupGui() {
        // ===== Title =====
        ImageView icon = new ImageView(Theme.ICON);
        icon.setFitWidth(42);
        icon.setFitHeight(42);
        Label title = new Label("GW2 Tab Converter");
        title.getStyleClass().add("app-title");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(12, icon, title, titleSpacer, makeThemeToggle());
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // ===== Score =====
        SVGPath noteIcon = new SVGPath();
        noteIcon.setContent("M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z");
        noteIcon.getStyleClass().add("file-icon");
        fileTitle.getStyleClass().add("file-title");
        fileTitle.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        fileHint.getStyleClass().add("file-hint");
        VBox fileContent = new VBox(4, noteIcon, fileTitle, fileHint);
        fileContent.setAlignment(Pos.CENTER);
        fileButton.setGraphic(fileContent);
        fileButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        fileButton.getStyleClass().add("file-button");
        fileButton.setMaxWidth(Double.MAX_VALUE);
        fileButton.setOnAction(e -> chooseFile());

        // ===== Options =====
        ChoiceBox<String> sharpChoiceBox = new ChoiceBox<>();
        sharpChoiceBox.getItems().addAll("#", "F#", "F");
        // The list shows the start of each style, and the chosen one is shown in full
        sharpChoiceBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String style) {
                return style == null ? "" : SHORT_SHARP_NAMES.getOrDefault(style, style);
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        });
        showFullSharpNames(sharpChoiceBox);
        sharpStyle = preferences.get("lastSharpStyle", "#");
        sharpChoiceBox.setValue(sharpStyle);
        sharpChoiceBox.setOnAction(event -> {
            sharpStyle = sharpChoiceBox.getValue();
            preferences.put("lastSharpStyle", sharpStyle);
        });

        ChoiceBox<Integer> measuresChoiceBox = new ChoiceBox<>();
        measuresChoiceBox.getItems().addAll(1, 2, 3, 4, 5);
        measuresPerRow = preferences.getInt("measuresPerRow", 2);
        measuresChoiceBox.setValue(measuresPerRow);
        measuresChoiceBox.setOnAction(event -> {
            measuresPerRow = measuresChoiceBox.getValue();
            preferences.putInt("measuresPerRow", measuresPerRow);
        });

        // Sharp Keys is just wide enough for its longest name, and Measures per Row gets the rest
        VBox sharpColumn = labeled("Sharp Keys", sharpChoiceBox);
        sharpColumn.setPrefWidth(160);
        sharpColumn.setMinWidth(Region.USE_PREF_SIZE);
        VBox measuresColumn = labeled("Measures per Row", measuresChoiceBox);
        HBox.setHgrow(measuresColumn, Priority.ALWAYS);
        HBox optionsRow = new HBox(14, sharpColumn, measuresColumn);

        CheckBox mergeStavesToggle = new CheckBox("Merge multi-staff instruments (e.g. grand staff)");
        mergeStavesToggle.setSelected(preferences.getBoolean("mergeStaves", true));
        mergeStavesToggle.setOnAction(event -> preferences.putBoolean("mergeStaves", mergeStavesToggle.isSelected()));

        // The paintbrush that opens the Tab Style window, level with the dropdowns
        optionsRow.getChildren().add(makeStyleButton());
        optionsRow.setAlignment(Pos.BOTTOM_LEFT);

        // ===== Convert =====
        convertButton.getStyleClass().add("convert");
        convertButton.setMaxWidth(Double.MAX_VALUE);
        convertButton.setDefaultButton(true);
        convertButton.setOnAction(e -> convert(mergeStavesToggle.isSelected()));

        VBox root = new VBox(20,
                titleRow,
                section("Score", fileButton),
                section("Options", optionsRow, mergeStavesToggle),
                convertButton);
        VBox.setMargin(convertButton, new Insets(6, 0, 0, 0));
        root.setPadding(new Insets(18, 22, 22, 22));
        root.setPrefWidth(400);

        // A score can also be dropped anywhere on the window
        root.setOnDragOver(event -> {
            if (!converting && draggedScore(event) != null) {
                event.acceptTransferModes(TransferMode.COPY);
                if (!fileButton.getStyleClass().contains("drag-over")) fileButton.getStyleClass().add("drag-over");
            }
            event.consume();
        });
        root.setOnDragExited(event -> fileButton.getStyleClass().remove("drag-over"));
        root.setOnDragDropped(event -> {
            File file = draggedScore(event);
            if (file != null) setFile(file);
            event.setDropCompleted(file != null);
            event.consume();
        });

        showFile();
        primaryStage.setTitle("GW2 Tab Converter");
        Theme.apply(primaryStage, new Scene(root));
        primaryStage.setResizable(false);
        primaryStage.show();
    }

    private static final Map<String, String> SHORT_SHARP_NAMES = Map.of(
            "#", "#1, #2, #4…", "F#", "F1, F2, F4…", "F", "F1, F2, F3…");
    private static final Map<String, String> FULL_SHARP_NAMES = Map.of(
            "#1, #2, #4…", "#1, #2, #4, #5, #6", "F1, F2, F4…", "F1, F2, F4, F5, F6", "F1, F2, F3…", "F1, F2, F3, F4, F5");

    // Swaps the short name the dropdown shows for the chosen style for its full one
    private static void showFullSharpNames(ChoiceBox<String> choiceBox) {
        choiceBox.skinProperty().addListener((observable, oldSkin, skin) -> {
            Node node = choiceBox.lookup(".label");
            if (!(node instanceof Label)) return;
            Label label = (Label) node;
            Runnable lengthen = () -> label.setText(FULL_SHARP_NAMES.getOrDefault(label.getText(), label.getText()));
            label.textProperty().addListener((textObservable, oldText, text) -> lengthen.run());
            lengthen.run();
        });
    }

    // Green paintbrush button, like the dropdowns beside it, that opens the Tab Style window
    private Button makeStyleButton() {
        // Lucide "paintbrush-vertical" (ISC licence), drawn as a line rather than filled
        SVGPath brush = new SVGPath();
        brush.setContent("M10 2v2 M14 2v4 M17 2a1 1 0 0 1 1 1v9H6V3a1 1 0 0 1 1-1z "
                + "M6 12a1 1 0 0 0-1 1v1a2 2 0 0 0 2 2h2a1 1 0 0 1 1 1v2.9a2 2 0 1 0 4 0V17a1 1 0 0 1 1-1h2"
                + "a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1");
        brush.setStrokeLineCap(StrokeLineCap.ROUND);
        brush.setStrokeLineJoin(StrokeLineJoin.ROUND);
        brush.setScaleX(0.88);
        brush.setScaleY(0.88);
        brush.getStyleClass().add("brush-icon");
        Button button = new Button();
        button.setGraphic(new Group(brush));
        button.getStyleClass().add("style-button");
        button.setTooltip(new Tooltip("Edit tab style and presets"));
        button.setOnAction(e -> StyleSettingsWindow.show(primaryStage, preferences));
        return button;
    }

    // Round button that switches between dark and light mode, showing a sun in dark mode and a moon in light mode
    private static Button makeThemeToggle() {
        Button toggle = new Button();
        toggle.getStyleClass().add("icon-button");
        Tooltip tooltip = new Tooltip();
        toggle.setTooltip(tooltip);
        Runnable showIcon = () -> {
            toggle.setGraphic(Theme.isDark() ? sunIcon() : moonIcon());
            tooltip.setText(Theme.isDark() ? "Switch to light mode" : "Switch to dark mode");
        };
        showIcon.run();
        toggle.setOnAction(e -> {
            Theme.setDark(!Theme.isDark());
            showIcon.run();
        });
        return toggle;
    }

    private static Node sunIcon() {
        Group sun = new Group(new Circle(0, 0, 4));
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45);
            Line ray = new Line(Math.cos(angle) * 6.5, Math.sin(angle) * 6.5, Math.cos(angle) * 8.5, Math.sin(angle) * 8.5);
            ray.setStrokeWidth(1.8);
            ray.setStrokeLineCap(StrokeLineCap.ROUND);
            sun.getChildren().add(ray);
        }
        sun.getChildren().forEach(shape -> shape.getStyleClass().add("icon-graphic"));
        return sun;
    }

    private static Node moonIcon() {
        SVGPath moon = new SVGPath();
        moon.setContent("M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9z");
        moon.setScaleX(0.72);
        moon.setScaleY(0.72);
        moon.setStrokeWidth(0);
        moon.getStyleClass().add("icon-graphic");
        return new Group(moon);
    }

    // ===== Actions =====
    private void chooseFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open MuseScore File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("MuseScore files (*.mscz, *.mscx)", "*.mscz", "*.mscx"));
        String lastDirectory = preferences.get("lastDirectory", null);
        if (lastDirectory != null) {
            File directory = new File(lastDirectory);
            if (directory.exists()) fileChooser.setInitialDirectory(directory);
        }
        File selectedFile = fileChooser.showOpenDialog(primaryStage);
        if (selectedFile != null) setFile(selectedFile);
    }

    private void setFile(File file) {
        uploadedFile = file;
        preferences.put("lastDirectory", file.getParent());
        showFile();
    }

    // The first .mscz or .mscx file being dragged, if any
    private static File draggedScore(DragEvent event) {
        if (!event.getDragboard().hasFiles()) return null;
        for (File file : event.getDragboard().getFiles()) {
            String name = file.getName().toLowerCase();
            if (name.endsWith(".mscz") || name.endsWith(".mscx")) return file;
        }
        return null;
    }

    private void convert(boolean mergeStaves) {
        if (uploadedFile == null || converting) return;
        converting = true;
        showFile();
        Runnable whenDone = () -> {
            converting = false;
            showFile();
        };
        new Conversion(uploadedFile, measuresPerRow, mergeStaves, sharpStyle,
                StyleSettings.load(preferences), whenDone).start();
    }

    // Updates the file and convert buttons
    private void showFile() {
        boolean hasFile = uploadedFile != null;
        fileTitle.setText(hasFile ? uploadedFile.getName() : "Choose a MuseScore File");
        fileHint.setText(hasFile ? "Click to choose a different file" : "or drop a .mscz or .mscx file here");
        if (hasFile && !fileButton.getStyleClass().contains("has-file")) fileButton.getStyleClass().add("has-file");
        fileButton.setDisable(converting);
        convertButton.setText(converting ? "Converting…" : "Convert & Copy");
        convertButton.setDisable(uploadedFile == null || converting);
    }

    // ===== Layout helpers =====
    static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("heading");
        return label;
    }

    // A heading with its controls close underneath, so each section reads as one group
    static VBox section(String title, Node... content) {
        VBox section = new VBox(10, heading(title));
        section.getChildren().addAll(content);
        return section;
    }

    // A small label above a control
    static VBox labeled(String text, Control control) {
        control.setMaxWidth(Double.MAX_VALUE);
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return new VBox(6, label, control);
    }
}

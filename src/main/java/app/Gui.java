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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
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
        sharpChoiceBox.getItems().addAll("#", "F");
        sharpChoiceBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String style) {
                return style == null ? "" : style.equals("F") ? "F1, F2, F3…" : "#1, #2, #4…";
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        });
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

        HBox optionsRow = twoColumns(14,
                labeled("Sharp Keys", sharpChoiceBox),
                labeled("Measures per Row", measuresChoiceBox));

        CheckBox mergeStavesToggle = new CheckBox("Merge multi-staff instruments (e.g. grand staff)");
        mergeStavesToggle.setSelected(preferences.getBoolean("mergeStaves", true));
        mergeStavesToggle.setOnAction(event -> preferences.putBoolean("mergeStaves", mergeStavesToggle.isSelected()));

        // ===== Style =====
        Button styleSettingsButton = new Button("Edit Style");
        styleSettingsButton.getStyleClass().add("secondary");
        styleSettingsButton.setOnAction(e -> StyleSettingsWindow.show(primaryStage, preferences));

        MenuItem loadPreset = new MenuItem("Load Preset…");
        loadPreset.setOnAction(e -> loadPreset());
        MenuItem savePreset = new MenuItem("Save Preset…");
        savePreset.setOnAction(e -> savePreset());
        MenuButton presetsButton = new MenuButton("Presets", null, loadPreset, savePreset);
        presetsButton.setAlignment(Pos.CENTER);
        presetsButton.getStyleClass().add("secondary");

        HBox styleRow = twoColumns(14, styleSettingsButton, presetsButton);

        // ===== Convert =====
        convertButton.getStyleClass().add("convert");
        convertButton.setMaxWidth(Double.MAX_VALUE);
        convertButton.setDefaultButton(true);
        convertButton.setOnAction(e -> convert(mergeStavesToggle.isSelected()));

        VBox root = new VBox(20,
                titleRow,
                section("Score", fileButton),
                section("Options", optionsRow, mergeStavesToggle),
                section("Style", styleRow),
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

    // Round button that switches between dark and light mode, showing a sun in dark mode and a moon in light mode
    private static Button makeThemeToggle() {
        Button toggle = new Button();
        toggle.getStyleClass().add("theme-toggle");
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
        sun.getChildren().forEach(shape -> shape.getStyleClass().add("theme-icon"));
        return sun;
    }

    private static Node moonIcon() {
        SVGPath moon = new SVGPath();
        moon.setContent("M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9z");
        moon.setScaleX(0.72);
        moon.setScaleY(0.72);
        moon.setStrokeWidth(0);
        moon.getStyleClass().add("theme-icon");
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

    private void loadPreset() {
        File file = presetChooser("Load Preset").showOpenDialog(primaryStage);
        if (file == null) return;
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
            StyleSettings.fromProperties(props).save(preferences);
            Dialogs.showInfo("Preset Loaded", "Loaded the style from " + file.getName() + ".");
        } catch (IOException ex) {
            Dialogs.showError("Could Not Load Preset", ex);
        }
    }

    private void savePreset() {
        File file = presetChooser("Save Preset").showSaveDialog(primaryStage);
        if (file == null) return;
        try (FileOutputStream fos = new FileOutputStream(file)) {
            StyleSettings.load(preferences).toProperties().store(fos, "GW2 Tab Converter Style Preset");
            Dialogs.showInfo("Preset Saved", "Saved the current style to " + file.getName() + ".");
        } catch (IOException ex) {
            Dialogs.showError("Could Not Save Preset", ex);
        }
    }

    private static FileChooser presetChooser(String title) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(title);
        fileChooser.setInitialDirectory(new File("."));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Properties files", "*.properties"));
        return fileChooser;
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

    // Two nodes side by side, each half the width
    static HBox twoColumns(double gap, Region left, Region right) {
        for (Region region : new Region[] {left, right}) {
            HBox.setHgrow(region, Priority.ALWAYS);
            region.setMaxWidth(Double.MAX_VALUE);
            region.setPrefWidth(0);
            // Without this, a wider minimum (like the Presets arrow) makes one side bigger
            region.setMinWidth(0);
        }
        return new HBox(gap, left, right);
    }
}

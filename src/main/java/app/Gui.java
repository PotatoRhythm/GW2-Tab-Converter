package app;

import tab.StyleSettings;

import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.*;
import javafx.scene.image.Image;
import javafx.geometry.*;
import javafx.stage.*;

import java.io.File;
import java.util.prefs.Preferences;
import java.util.Properties;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class Gui {
    private final Stage primaryStage;
    private File uploadedFile;
    private final Preferences preferences;

    // User options
    private int measuresPerRow = 2;
    private String sharpStyle = "#";

    public Gui(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.preferences = Preferences.userNodeForPackage(Gui.class);
    }

    public void setupGui() {
        primaryStage.setTitle("GW2 Tab Converter");
        Image icon = new Image(Gui.class.getResource("converterIcon.png").toExternalForm());
        primaryStage.getIcons().add(icon);

        // ===== Title =====
        Label titleLabel = new Label("GW2 Tab Converter");
        titleLabel.setFont(Font.font("Times New Roman", FontWeight.BOLD, 42));
        titleLabel.setStyle("-fx-text-fill: green;");

        // ===== Upload MuseScore File =====
        Button uploadButton = new Button("Upload MuseScore File");
        uploadButton.setPrefWidth(250);
        uploadButton.setPrefHeight(45);
        uploadButton.setOnAction(e -> {
            String lastDirectory = preferences.get("lastDirectory", null);
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Open MuseScore File");
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("MuseScore files (*.mscz, *.mscx)", "*.mscz", "*.mscx"));
            if (lastDirectory != null) {
                File directory = new File(lastDirectory);
                if (directory.exists()) fileChooser.setInitialDirectory(directory);
            }
            File selectedFile = fileChooser.showOpenDialog(primaryStage);
            if (selectedFile != null) {
                uploadedFile = selectedFile;
                uploadButton.setText(uploadedFile.getName());
                preferences.put("lastDirectory", selectedFile.getParent());
            }
        });

        // ===== Sharp Style Dropdown =====
        Label sharpStyleLabel = new Label("Sharp Style:");
        ChoiceBox<String> sharpChoiceBox = new ChoiceBox<>();
        sharpChoiceBox.getItems().addAll("#", "F");
        String savedSharpStyle = preferences.get("lastSharpStyle", "#");
        sharpChoiceBox.setValue(savedSharpStyle);
        sharpStyle = savedSharpStyle;
        sharpChoiceBox.setOnAction(event -> {
            sharpStyle = sharpChoiceBox.getValue();
            preferences.put("lastSharpStyle", sharpStyle);
        });
        VBox sharpBox = new VBox(5, sharpStyleLabel, sharpChoiceBox);
        sharpBox.setAlignment(Pos.CENTER);
        sharpBox.setPrefWidth(120);

        // ===== Measures/Row Dropdown =====
        Label measuresLabel = new Label("Measures/Row:");
        ChoiceBox<Integer> measuresChoiceBox = new ChoiceBox<>();
        measuresChoiceBox.getItems().addAll(1, 2, 3, 4, 5);
        measuresPerRow = preferences.getInt("measuresPerRow", 2);
        measuresChoiceBox.setValue(measuresPerRow);
        measuresChoiceBox.setOnAction(event -> {
            measuresPerRow = measuresChoiceBox.getValue();
            preferences.putInt("measuresPerRow", measuresPerRow);
        });
        VBox measuresBox = new VBox(5, measuresLabel, measuresChoiceBox);
        measuresBox.setAlignment(Pos.CENTER);
        measuresBox.setPrefWidth(140);

        HBox settingsRow = new HBox(40, sharpBox, measuresBox);
        settingsRow.setAlignment(Pos.CENTER);

        // ===== Merge Staves Toggle =====
        CheckBox mergeStavesToggle = new CheckBox("Merge multi-staff instruments (e.g. piano)");
        mergeStavesToggle.setSelected(preferences.getBoolean("mergeStaves", true));
        mergeStavesToggle.setOnAction(event -> preferences.putBoolean("mergeStaves", mergeStavesToggle.isSelected()));

        // ===== Convert Button =====
        Button convertButton = new Button("Convert");
        convertButton.setPrefWidth(150);
        convertButton.setPrefHeight(40);
        convertButton.setOnAction(e -> {
            if (uploadedFile == null) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(null);
                alert.setContentText("Please upload a MuseScore file before converting.");
                alert.showAndWait();
                return;
            }
            convertButton.setDisable(true);
            convertButton.setText("Converting...");
            Runnable whenDone = () -> {
                convertButton.setDisable(false);
                convertButton.setText("Convert");
            };
            new Conversion(uploadedFile, measuresPerRow, mergeStavesToggle.isSelected(), sharpStyle,
                    StyleSettings.load(preferences), whenDone).start();
        });

        // ===== Style Buttons =====
        Button styleSettingsButton = new Button("⚙ Style Settings");
        styleSettingsButton.setOnAction(e -> StyleSettingsWindow.show(primaryStage, preferences));

        Button loadPresetButton = new Button("Load Preset");
        loadPresetButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Load Preset");
            fileChooser.setInitialDirectory(new File("."));
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Properties files", "*.properties"));
            File file = fileChooser.showOpenDialog(primaryStage);
            if (file != null) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(file)) {
                    props.load(fis);
                    StyleSettings.fromProperties(props).save(preferences);
                    Dialogs.showInfo("Preset Loaded", "Preset loaded successfully!");
                } catch (IOException ex) {
                    Dialogs.showError("Could Not Load Preset", ex);
                }
            }
        });

        Button savePresetButton = new Button("Save Preset");
        savePresetButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Save Preset");
            fileChooser.setInitialDirectory(new File("."));
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Properties files", "*.properties"));
            File file = fileChooser.showSaveDialog(primaryStage);
            if (file != null) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    StyleSettings.load(preferences).toProperties().store(fos, "GW2 Tab Converter Style Preset");
                    Dialogs.showInfo("Preset Saved", "Preset saved successfully!");
                } catch (IOException ex) {
                    Dialogs.showError("Could Not Save Preset", ex);
                }
            }
        });

        HBox bottomButtonBox = new HBox(15, styleSettingsButton, loadPresetButton, savePresetButton);
        bottomButtonBox.setAlignment(Pos.CENTER);

        VBox root = new VBox(25, titleLabel, uploadButton, settingsRow, mergeStavesToggle, convertButton, bottomButtonBox);
        root.setAlignment(Pos.CENTER);
        Scene scene = new Scene(root, 430, 410);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

}

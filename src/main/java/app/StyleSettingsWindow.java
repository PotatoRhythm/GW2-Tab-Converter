package app;

import tab.StyleSettings;

import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.geometry.*;
import javafx.stage.*;
import javafx.scene.paint.Color;

import java.util.function.Consumer;
import java.util.prefs.Preferences;

// Style Settings window
final class StyleSettingsWindow {
    private StyleSettingsWindow() {
    }

    static void show(Stage owner, Preferences preferences) {
        Stage settingsStage = new Stage();
        settingsStage.setTitle("Style Settings");
        settingsStage.initOwner(owner);
        settingsStage.initModality(Modality.APPLICATION_MODAL);

        Label borderStyleLabel = new Label("Border Style:");
        ChoiceBox<String> borderStyleChoice = new ChoiceBox<>();
        borderStyleChoice.getItems().addAll("Standard", "Box");

        CheckBox borderTopToggle = new CheckBox("Border Color Top");
        ColorPicker borderTopColor = new ColorPicker();
        CheckBox borderSidesToggle = new CheckBox("Border Color Sides");
        ColorPicker borderSidesColor = new ColorPicker();
        CheckBox borderBottomToggle = new CheckBox("Border Color Bottom");
        ColorPicker borderBottomColor = new ColorPicker();
        CheckBox boxColorToggle = new CheckBox("Box Color");
        ColorPicker boxColorPicker = new ColorPicker();

        // ===== Closing Text Option =====
        CheckBox closingTextToggle = new CheckBox("Closing Text");
        TextArea closingTextField = new TextArea();
        closingTextField.setPromptText("Enter closing text...");
        closingTextField.setPrefWidth(180);
        closingTextField.setPrefRowCount(1);

        VBox borderBox = new VBox(8,
                borderStyleLabel, borderStyleChoice,
                makeRow(borderTopToggle, borderTopColor),
                makeRow(borderSidesToggle, borderSidesColor),
                makeRow(borderBottomToggle, borderBottomColor),
                makeRow(boxColorToggle, boxColorPicker),
                makeRow(closingTextToggle, closingTextField)
        );
        borderBox.setPadding(new Insets(10));

        // Measure Highlights
        Label measureHighlightLabel = new Label("Measure Highlights:");
        ChoiceBox<String> measureHighlightChoice = new ChoiceBox<>();
        measureHighlightChoice.getItems().addAll("None", "First Beat", "Strong Beats");
        Label highlight1Label = new Label("Highlight Color 1:");
        ColorPicker highlightColor1 = new ColorPicker();
        Label highlight2Label = new Label("Highlight Color 2:");
        ColorPicker highlightColor2 = new ColorPicker();

        VBox measureBox = new VBox(8,
                measureHighlightLabel, measureHighlightChoice,
                highlight1Label, highlightColor1,
                highlight2Label, highlightColor2
        );
        measureBox.setPadding(new Insets(10));

        CheckBox sharpColorToggle = new CheckBox("Sharp Color");
        ColorPicker sharpColorPicker = new ColorPicker();
        VBox sharpBox = new VBox(8, new Label("Sharp Color:"), makeRow(sharpColorToggle, sharpColorPicker));
        sharpBox.setPadding(new Insets(10));

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
            sharpColorToggle.setSelected(style.sharpColorEnabled);
            sharpColorPicker.setValue(Color.web(style.sharpColor));
        };
        showStyle.accept(StyleSettings.load(preferences));

        Button applyButton = new Button("Apply");
        applyButton.setOnAction(e -> {
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
            style.sharpColorEnabled = sharpColorToggle.isSelected();
            style.sharpColor = toHex(sharpColorPicker.getValue());
            style.closingTextEnabled = closingTextToggle.isSelected();
            style.closingText = closingTextField.getText();
            style.save(preferences);
        });

        Button resetButton = new Button("Reset Style");
        resetButton.setOnAction(e -> {
            StyleSettings defaults = new StyleSettings();
            showStyle.accept(defaults);
            defaults.save(preferences);
        });

        Button cancelButton = new Button("Close");
        cancelButton.setOnAction(e -> settingsStage.close());

        HBox buttonBox = new HBox(15, applyButton, resetButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER);

        VBox layout = new VBox(15, borderBox, measureBox, sharpBox, buttonBox);
        layout.setPadding(new Insets(15));
        layout.setAlignment(Pos.CENTER_LEFT);

        Scene scene = new Scene(layout, 350, 650);
        settingsStage.setScene(scene);
        settingsStage.showAndWait();
    }

    // Converts a Color to #RRGGBB format
    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    private static HBox makeRow(Control toggle, Control control) {
        HBox row = new HBox(10, toggle, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}

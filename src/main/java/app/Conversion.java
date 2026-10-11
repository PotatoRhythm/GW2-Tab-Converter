package app;

import reader.Preprocessor;
import tab.Parser;
import tab.StyleSettings;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

// Converts score
class Conversion {
    // Reads the score. Called again with approximateTiming on if notes that don't fit exactly should be added
    interface ScoreLoader {
        Preprocessor load(boolean approximateTiming) throws Exception;
    }

    private final File file;
    private final ScoreLoader loader;
    private final int measuresPerRow;
    private final String sharpStyle;
    private final StyleSettings style;
    private final Runnable whenDone;

    // whenDone runs once the conversion finishes, fails or is cancelled
    Conversion(File file, ScoreLoader loader, int measuresPerRow, String sharpStyle, StyleSettings style, Runnable whenDone) {
        this.file = file;
        this.loader = loader;
        this.measuresPerRow = measuresPerRow;
        this.sharpStyle = sharpStyle;
        this.style = style;
        this.whenDone = whenDone;
    }

    // Reads the score, asks what to do with notes that don't fit exactly (if any), then copies the tab
    void start() {
        runInBackground(() -> loader.load(false), preprocessor -> {
            if (preprocessor.getDroppedNotes().isEmpty()) {
                writeTab(preprocessor);
                return;
            }
            Optional<Boolean> approximate = askToApproximate(preprocessor);
            if (approximate.isEmpty()) {
                whenDone.run();
            } else if (approximate.get()) {
                runInBackground(() -> loader.load(true),
                        approximated -> writeTab(approximated));
            } else {
                writeTab(preprocessor);
            }
        });
    }

    private void writeTab(Preprocessor preprocessor) {
        runInBackground(() -> new Parser(preprocessor.getProcessedInstruments(), preprocessor.getBoxes(), style,
                sharpStyle, measuresPerRow, preprocessor.getTitle()), parser -> {
            parser.copyToClipboard();
            whenDone.run();

            List<String> warnings = new ArrayList<>(preprocessor.getWarnings());
            for (String dropped : preprocessor.getDroppedNotes()) {
                warnings.add("Left out: " + dropped);
            }
            warnings.addAll(parser.getWarnings());
            Alert alert;
            if (warnings.isEmpty()) {
                alert = copiedPopup();
            } else {
                alert = Theme.apply(new Alert(Alert.AlertType.WARNING));
                alert.setTitle("Converted With Warnings");
                alert.setContentText("Table copied to clipboard, but:\n\n" + Dialogs.formatList(warnings));
            }
            alert.setHeaderText(null);
            alert.showAndWait();
        });
    }

    static Alert copiedPopup() {
        Alert alert = Theme.apply(new Alert(Alert.AlertType.INFORMATION));
        alert.setTitle("Copied to Clipboard");
        Label copied = new Label("The tab has been copied to your clipboard.");
        copied.getStyleClass().add("popup-title");
        alert.getDialogPane().setContent(new VBox(4, copied, new Label("Paste it into Google Docs.")));
        alert.getDialogPane().setGraphic(clipboardIcon());
        alert.getDialogPane().setPrefWidth(388);
        alert.getDialogPane().getStyleClass().add("copied-popup");
        return alert;
    }

    // Lucide "clipboard-check" (ISC licence) in place of the blue information icon
    private static Node clipboardIcon() {
        return outlineIcon("M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z "
                + "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2 M9 14l2 2 4-4");
    }

    // Lucide "triangle-alert" (ISC licence) in place of the blue question mark
    private static Node warningIcon() {
        return outlineIcon("M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3z M12 9v4 M12 17h.01");
    }

    // A green outline icon drawn on a 24 x 24 grid, shown larger and nudged right and down so it has a margin
    // and sits level with the text beside it
    private static Node outlineIcon(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.setStrokeLineCap(StrokeLineCap.ROUND);
        icon.setStrokeLineJoin(StrokeLineJoin.ROUND);
        icon.setScaleX(1.5);
        icon.setScaleY(1.5);
        icon.getStyleClass().add("outline-icon");
        StackPane holder = new StackPane(new Group(icon));
        holder.setPadding(new Insets(4, 2, 0, 6));
        return holder;
    }

    // Keep windows responsive during conversion
    private <T> void runInBackground(Callable<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> {
            whenDone.run();
            Dialogs.showError("Could not convert " + file.getName(), task.getException());
        });
        Thread thread = new Thread(task, "Conversion");
        thread.setDaemon(true);
        thread.start();
    }

    // Asks whether notes that can't be placed exactly should be added at the nearest timing (true)
    // or left out (false). Empty if the user cancels the conversion
    private Optional<Boolean> askToApproximate(Preprocessor preprocessor) {
        ButtonType addButton = new ButtonType("Add Them", ButtonBar.ButtonData.YES);
        ButtonType dropButton = new ButtonType("Leave Them Out", ButtonBar.ButtonData.NO);
        Alert alert = Theme.apply(new Alert(Alert.AlertType.CONFIRMATION, "", addButton, dropButton, ButtonType.CANCEL));
        alert.getDialogPane().setGraphic(warningIcon());
        alert.getDialogPane().getStyleClass().add("fit-popup");
        alert.setTitle("Notes Don't Fit Exactly");
        alert.setHeaderText("Some notes can't be merged exactly.");
        String explanation = "Add them anyway?";
        if (preprocessor.hasUnalignedTupletNotes()) {
            explanation += "\n- The tuplet's notes move to the nearest 32nd so every note fits. "
                    + "The tuplet becomes regular (often dotted) notes.";
        }
        if (preprocessor.hasExtraMeasures()) {
            explanation += "\n- Staff with extra measures: empty measures are added to the other staves so the extra ones fit. "
                    + "Leaving them out cuts the extra measures instead.";
        }
        alert.setContentText(Dialogs.formatList(preprocessor.getDroppedNotes()) + "\n\n" + explanation);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) {
            return Optional.empty();
        }
        return Optional.of(result.get() == addButton);
    }
}

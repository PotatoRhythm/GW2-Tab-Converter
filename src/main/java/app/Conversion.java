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
    private final File file;
    private final int measuresPerRow;
    private final boolean mergeStaves;
    private final String sharpStyle;
    private final StyleSettings style;
    private final Runnable whenDone;

    // whenDone runs once the conversion finishes, fails or is cancelled
    Conversion(File file, int measuresPerRow, boolean mergeStaves, String sharpStyle, StyleSettings style, Runnable whenDone) {
        this.file = file;
        this.measuresPerRow = measuresPerRow;
        this.mergeStaves = mergeStaves;
        this.sharpStyle = sharpStyle;
        this.style = style;
        this.whenDone = whenDone;
    }

    // Reads the score, asks what to do with notes that don't fit exactly (if any), then copies the tab
    void start() {
        runInBackground(() -> new Preprocessor(file, measuresPerRow, mergeStaves, false), preprocessor -> {
            if (preprocessor.getDroppedNotes().isEmpty()) {
                writeTab(preprocessor);
                return;
            }
            Optional<Boolean> approximate = askToApproximate(preprocessor);
            if (approximate.isEmpty()) {
                whenDone.run();
            } else if (approximate.get()) {
                runInBackground(() -> new Preprocessor(file, measuresPerRow, mergeStaves, true),
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

    // Lucide "clipboard-check" (ISC licence) in place of the blue information icon,
    // nudged right and down so it has a margin and sits level with the two lines of text
    private static Node clipboardIcon() {
        SVGPath clipboard = new SVGPath();
        clipboard.setContent("M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z "
                + "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2 M9 14l2 2 4-4");
        clipboard.setStrokeLineCap(StrokeLineCap.ROUND);
        clipboard.setStrokeLineJoin(StrokeLineJoin.ROUND);
        clipboard.setScaleX(1.5);
        clipboard.setScaleY(1.5);
        clipboard.getStyleClass().add("clipboard-icon");
        StackPane holder = new StackPane(new Group(clipboard));
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
        alert.setTitle("Notes Don't Fit Exactly");
        alert.setHeaderText("Some notes can't be merged exactly.");
        String explanation = "Add them anyway?";
        if (preprocessor.hasUnalignedTupletNotes()) {
            explanation += "\n- Measures with a tuplet: the tuplet's notes move to the nearest 32nd so every note fits. "
                    + "The tuplet becomes regular (often dotted) notes and the measure keeps its length.";
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

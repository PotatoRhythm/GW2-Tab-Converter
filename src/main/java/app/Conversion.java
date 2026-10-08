package app;

import reader.Preprocessor;
import tab.Parser;
import tab.StyleSettings;

import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

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
                alert = Theme.apply(new Alert(Alert.AlertType.INFORMATION));
                alert.setTitle("Copied to Clipboard");
                Label copied = new Label("The tab has been copied to your clipboard.");
                copied.getStyleClass().add("popup-title");
                alert.getDialogPane().setContent(new VBox(4, copied, new Label("Paste it into Google Docs.")));
                alert.getDialogPane().setPrefWidth(415);
            } else {
                alert = Theme.apply(new Alert(Alert.AlertType.WARNING));
                alert.setTitle("Converted With Warnings");
                alert.setContentText("Table copied to clipboard, but:\n\n" + Dialogs.formatList(warnings));
            }
            alert.setHeaderText(null);
            alert.showAndWait();
        });
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

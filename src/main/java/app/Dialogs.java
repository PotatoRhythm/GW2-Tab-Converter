package app;

import javafx.scene.control.*;

import java.util.List;

// Dialog popups 
final class Dialogs {
    private Dialogs() {
    }

    static void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    static void showError(String header, Throwable error) {
        error.printStackTrace();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(header);
        alert.setContentText(error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
        alert.showAndWait();
    }

    // One item per line, shortened so long lists still fit in a dialog
    static String formatList(List<String> items) {
        int shown = Math.min(items.size(), 10);
        String text = String.join("\n", items.subList(0, shown));
        if (items.size() > shown) {
            text += "\n...and " + (items.size() - shown) + " more";
        }
        return text;
    }
}

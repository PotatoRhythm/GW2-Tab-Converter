package app;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.util.List;
import java.util.prefs.Preferences;

// Shared stylesheet, window icon and light/dark mode
final class Theme {
    static final Image ICON = new Image(Theme.class.getResource("converterIcon.png").toExternalForm());
    private static final String STYLESHEET = Theme.class.getResource("app.css").toExternalForm();
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(Gui.class);
    private static boolean dark = PREFERENCES.getBoolean("darkMode", true);

    private Theme() {
    }

    static boolean isDark() {
        return dark;
    }

    // Switches every open window to the chosen mode and remembers it
    static void setDark(boolean dark) {
        Theme.dark = dark;
        PREFERENCES.putBoolean("darkMode", dark);
        for (Window window : Window.getWindows()) {
            if (window.getScene() != null) showMode(window.getScene().getRoot());
            if (window instanceof Stage) TitleBar.apply((Stage) window, dark);
        }
    }

    static void apply(Stage stage, Scene scene) {
        style(stage, scene);
        stage.setScene(scene);
    }

    // How far right of centre popups and the Style window open, as a share of the main window's width,
    // so part of the main window stays in view
    private static final double RIGHT_SHIFT = 0.4;

    // Popups also get the app's current window as their owner, and open over it shifted right
    static Alert apply(Alert alert) {
        Scene scene = alert.getDialogPane().getScene();
        style((Stage) scene.getWindow(), scene);
        Window owner = activeWindow();
        if (alert.getOwner() == null && owner != null) alert.initOwner(owner);
        // Placed just before showing, once the text is in, so it opens straight in its spot
        alert.setOnShowing(event -> {
            if (alert.getOwner() == null) return;
            Point2D spot = placeOverOwner(alert.getOwner(), alert.getDialogPane());
            alert.setX(spot.getX());
            alert.setY(spot.getY());
        });
        return alert;
    }

    // Places an owned window over its owner, shifted right. Call after setting the scene
    static void placeOverOwner(Stage stage) {
        if (stage.getOwner() == null) return;
        Point2D spot = placeOverOwner(stage.getOwner(), stage.getScene().getRoot());
        stage.setX(spot.getX());
        stage.setY(spot.getY());
    }

    // Where a window holding this content should go: vertically centred on the owner, shifted right of its centre,
    // and kept inside the owner's screen
    private static Point2D placeOverOwner(Window owner, Parent content) {
        content.applyCss();
        double width = content.prefWidth(-1);
        // The owner's scene starts below its title bar, so that offset is the title bar height
        double titleBar = owner.getScene().getY();
        double height = content.prefHeight(width) + titleBar;
        List<Screen> screens = Screen.getScreensForRectangle(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight());
        Rectangle2D bounds = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
        double x = owner.getX() + (owner.getWidth() - width) / 2 + owner.getWidth() * RIGHT_SHIFT;
        double y = owner.getY() + (owner.getHeight() - height) / 2;
        return new Point2D(Math.max(bounds.getMinX(), Math.min(x, bounds.getMaxX() - width)),
                Math.max(bounds.getMinY(), Math.min(y, bounds.getMaxY() - height)));
    }

    // The app window the user is using: the focused one, or else the first one open
    private static Window activeWindow() {
        Window first = null;
        for (Window window : Window.getWindows()) {
            if (!(window instanceof Stage) || !window.isShowing()) continue;
            if (window.isFocused()) return window;
            if (first == null) first = window;
        }
        return first;
    }

    private static void style(Stage stage, Scene scene) {
        scene.getStylesheets().add(STYLESHEET);
        showMode(scene.getRoot());
        stage.getIcons().add(ICON);
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> TitleBar.apply(stage, dark));
    }

    // Light mode swaps the colours defined under .root.light in app.css
    private static void showMode(Parent root) {
        root.getStyleClass().remove("light");
        if (!dark) root.getStyleClass().add("light");
    }
}

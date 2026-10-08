package app;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import javafx.stage.Stage;

// Colours the Windows title bar and window border to match the theme.
// Needs Windows 11 for the custom colours (Windows 10 only gets the dark title bar), and does nothing elsewhere
final class TitleBar {
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    private static final String HANDLE_KEY = "TitleBar.handle";

    // Window attributes from dwmapi.h
    private static final int USE_IMMERSIVE_DARK_MODE = 20;
    private static final int BORDER_COLOR = 34;
    private static final int CAPTION_COLOR = 35;
    private static final int TEXT_COLOR = 36;

    private interface User32 extends Library {
        Pointer FindWindowW(WString className, WString windowName);
    }

    private interface Dwmapi extends Library {
        int DwmSetWindowAttribute(Pointer window, int attribute, IntByReference value, int size);
    }

    private static User32 user32;
    private static Dwmapi dwmapi;

    private TitleBar() {
    }

    // Call once the stage is showing
    static void apply(Stage stage, boolean dark) {
        if (!WINDOWS) return;
        try {
            Pointer window = handle(stage);
            if (window == null) return;
            set(window, USE_IMMERSIVE_DARK_MODE, dark ? 1 : 0);
            set(window, CAPTION_COLOR, colorRef(dark ? 0x242424 : 0xE6EBE8));
            set(window, BORDER_COLOR, colorRef(dark ? 0x4A4D4F : 0xC3CCC6));
            set(window, TEXT_COLOR, colorRef(dark ? 0xDCE4EE : 0x1F2A24));
        } catch (Throwable error) {
            // Keep the normal title bar if Windows or JNA can't do it
        }
    }

    // Finds the stage's native window by briefly giving it a title no other window has
    private static Pointer handle(Stage stage) {
        Object saved = stage.getProperties().get(HANDLE_KEY);
        if (saved != null) return (Pointer) saved;
        if (user32 == null) {
            user32 = Native.load("user32", User32.class);
            dwmapi = Native.load("dwmapi", Dwmapi.class);
        }
        String title = stage.getTitle();
        String marker = "GW2TabConverter-" + System.nanoTime();
        stage.setTitle(marker);
        Pointer window = user32.FindWindowW(null, new WString(marker));
        stage.setTitle(title);
        if (window != null) stage.getProperties().put(HANDLE_KEY, window);
        return window;
    }

    private static void set(Pointer window, int attribute, int value) {
        dwmapi.DwmSetWindowAttribute(window, attribute, new IntByReference(value), 4);
    }

    // Windows wants colours as 0x00BBGGRR
    private static int colorRef(int rgb) {
        return ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }
}

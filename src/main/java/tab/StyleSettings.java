package tab;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.prefs.Preferences;

// How the converted tab looks. Saved in the user's preferences and in preset files 
public class StyleSettings {
    // Default style
    public String borderStyle = "Standard";
    public boolean borderTopEnabled = true;
    public String borderTopColor = "#999999";
    public boolean borderSidesEnabled = true;
    public String borderSidesColor = "#b7b7b7";
    public boolean borderBottomEnabled = true;
    public String borderBottomColor = "#b7b7b7";
    public boolean boxColorEnabled = true;
    public String boxColor = "#d9d9d9";
    public String measureHighlight = "First Beat";
    public String highlightColor1 = "#f3f3f3";
    public String highlightColor2 = "#b7b7b7";
    public boolean sharpColorEnabled = true;
    public String sharpColor = "#006400";
    public boolean closingTextEnabled = false;
    public String closingText = "";

    public static StyleSettings load(Preferences prefs) {
        return read(key -> prefs.get(key, null));
    }

    public void save(Preferences prefs) {
        toMap().forEach(prefs::put);
    }

    public static StyleSettings fromProperties(Properties props) {
        return read(props::getProperty);
    }

    public Properties toProperties() {
        Properties props = new Properties();
        props.putAll(toMap());
        return props;
    }

    private Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("borderStyle", borderStyle);
        map.put("borderTopEnabled", Boolean.toString(borderTopEnabled));
        map.put("borderTopColor", borderTopColor);
        map.put("borderSidesEnabled", Boolean.toString(borderSidesEnabled));
        map.put("borderSidesColor", borderSidesColor);
        map.put("borderBottomEnabled", Boolean.toString(borderBottomEnabled));
        map.put("borderBottomColor", borderBottomColor);
        map.put("boxColorEnabled", Boolean.toString(boxColorEnabled));
        map.put("boxColor", boxColor);
        map.put("measureHighlight", measureHighlight);
        map.put("highlightColor1", highlightColor1);
        map.put("highlightColor2", highlightColor2);
        map.put("sharpColorEnabled", Boolean.toString(sharpColorEnabled));
        map.put("sharpColor", sharpColor);
        map.put("closingTextEnabled", Boolean.toString(closingTextEnabled));
        map.put("closingText", closingText);
        return map;
    }

    private static StyleSettings read(Function<String, String> source) {
        StyleSettings s = new StyleSettings();
        s.borderStyle = text(source, "borderStyle", s.borderStyle);
        s.borderTopEnabled = flag(source, "borderTopEnabled", s.borderTopEnabled);
        s.borderTopColor = text(source, "borderTopColor", s.borderTopColor);
        s.borderSidesEnabled = flag(source, "borderSidesEnabled", s.borderSidesEnabled);
        s.borderSidesColor = text(source, "borderSidesColor", s.borderSidesColor);
        s.borderBottomEnabled = flag(source, "borderBottomEnabled", s.borderBottomEnabled);
        s.borderBottomColor = text(source, "borderBottomColor", s.borderBottomColor);
        s.boxColorEnabled = flag(source, "boxColorEnabled", s.boxColorEnabled);
        s.boxColor = text(source, "boxColor", s.boxColor);
        s.measureHighlight = text(source, "measureHighlight", s.measureHighlight);
        s.highlightColor1 = text(source, "highlightColor1", s.highlightColor1);
        s.highlightColor2 = text(source, "highlightColor2", s.highlightColor2);
        s.sharpColorEnabled = flag(source, "sharpColorEnabled", s.sharpColorEnabled);
        s.sharpColor = text(source, "sharpColor", s.sharpColor);
        s.closingTextEnabled = flag(source, "closingTextEnabled", s.closingTextEnabled);
        s.closingText = text(source, "closingText", s.closingText);
        return s;
    }

    private static String text(Function<String, String> source, String key, String defaultValue) {
        String value = source.apply(key);
        return value != null ? value : defaultValue;
    }

    private static boolean flag(Function<String, String> source, String key, boolean defaultValue) {
        String value = source.apply(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
}

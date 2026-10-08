package tab;

import java.util.Map;

// Which key to press for each pitch, and which octave it's in, on each instrument
class InstrumentKeys {
    // Keys of an instrument with every note (C = 1), by pitch class (C = 0, C# = 1, ...); null for sharps
    private static final String[] CHROMATIC_KEYS = {"1", null, "2", null, "3", "4", null, "5", null, "6", null, "7"};
    // Which black key (0-4) each sharp is, by pitch class
    private static final int[] BLACK_KEYS = {-1, 0, -1, 1, -1, -1, 2, -1, 3, -1, 4, -1};
    private static final String[] SHARP_NAMES = {"#1", "#2", "#4", "#5", "#6"};
    private static final String[] F_KEY_NAMES = {"F1", "F2", "F3", "F4", "F5"};
    // Keys of the instruments that only play one scale, by pitch class; null where there is no key
    private static final String[] CHOIR_BELL_KEYS = {"7", null, "1", null, "2", "3", null, "4", null, "5", null, "6"};
    private static final String[] HORN_FLUTE_KEYS = {null, "6", null, "7", "1", null, "2", "3", null, "4", null, "5"};
    private static final Map<Integer, String> FRAME_DRUM_KEYS = Map.of(
            60, "1", 79, "1", 62, "2", 77, "2", 64, "3", 76, "3", 65, "4", 74, "4", 67, "5", 72, "5");
    // Drum keys above its chromatic octave (60-71)
    private static final Map<Integer, String> DRUM_HIGH_KEYS = Map.of(72, "8", 74, "9", 76, "0");
    private static final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    private final String sharpStyle;
    private final StyleSettings style;

    // sharpStyle is "#" or "F"
    InstrumentKeys(String sharpStyle, StyleSettings style) {
        this.sharpStyle = sharpStyle;
        this.style = style;
    }

    // The key to press for a pitch on the instrument
    String getKey(int pitch, String instrument) {
        int pitchClass = Math.floorMod(pitch, 12);
        String key;
        switch (instrument) {
            case "Choir Bell":
                key = CHOIR_BELL_KEYS[pitchClass];
                break;
            case "Horn":
            case "Flute":
                key = HORN_FLUTE_KEYS[pitchClass];
                break;
            case "Frame Drum":
                key = FRAME_DRUM_KEYS.get(pitch);
                break;
            case "Drum":
                key = pitch >= 60 && pitch <= 71 ? getChromaticKey(pitchClass) : DRUM_HIGH_KEYS.get(pitch);
                break;
            default:
                key = getChromaticKey(pitchClass);
                break;
        }
        return key != null ? key : "?";
    }

    // Key for a pitch class on an instrument with every note, with sharps in the chosen style and colour
    private String getChromaticKey(int pitchClass) {
        int blackKey = BLACK_KEYS[pitchClass];
        if (blackKey == -1) {
            return CHROMATIC_KEYS[pitchClass];
        }
        String sharp = sharpStyle.equals("F") ? F_KEY_NAMES[blackKey] : SHARP_NAMES[blackKey];
        if (style.sharpColorEnabled) {
            return "<span style=\"color: " + style.sharpColor + ";\">" + sharp + "</span>";
        }
        return sharp;
    }

    static int getOctave(int pitch, String instrument) {
        switch (instrument) {
            case "Choir Bell":
                return getOctave(pitch, 62, 1, 3);
            case "Horn":
            case "Flute":
                return getOctave(pitch, 64, 1, 3);
            case "Drum":
                return 2;
            case "Verdarach":
                return getOctave(pitch, 36, 1, 3);
            case "Bass":
                return getOctave(pitch, 48, 1, 3);
            default:
                return getOctave(pitch, 60, -1, 5);
        }
    }

    // Octave 2 starts at octaveTwoStart and each octave is 12 semitones, kept between lowest and highest
    private static int getOctave(int pitch, int octaveTwoStart, int lowest, int highest) {
        int octave = Math.floorDiv(pitch - octaveTwoStart, 12) + 2;
        return Math.max(lowest, Math.min(highest, octave));
    }

    // Name of a pitch like "C4"
    static String noteName(int pitch) {
        return NOTE_NAMES[Math.floorMod(pitch, 12)] + (Math.floorDiv(pitch, 12) - 1);
    }
}

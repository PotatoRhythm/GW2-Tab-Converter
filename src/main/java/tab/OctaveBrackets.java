package tab;

import java.util.Map;

// The brackets that move notes into other octaves: [ ] -1 ⟦ ⟧ -2, ( ) +1 ⸨ ⸩ +2
final class OctaveBrackets {
    private OctaveBrackets() {
    }

    private static final Map<Integer, String> OCTAVE_OPENERS = Map.of(
            -2, "⟦⟦", -1, "⟦[", 0, "⟦", 1, "[", 3, "(", 4, "⸨", 5, "⸨(", 6, "⸨⸨");
    private static final Map<Integer, String> OCTAVE_CLOSERS = Map.of(
            -2, "⟧⟧", -1, "]⟧", 0, "⟧", 1, "]", 3, ")", 4, "⸩", 5, ")⸩", 6, "⸩⸩");

    static void preOctaveHelper(StringBuilder rowText, int prevOctave, int currentOctave) {
        if (prevOctave != currentOctave && OCTAVE_OPENERS.containsKey(currentOctave)) {
            rowText.append(OCTAVE_OPENERS.get(currentOctave));
        }
    }

    static void postOctaveHelper(StringBuilder rowText, int currentOctave, int nextOctave) {
        if (currentOctave != nextOctave && OCTAVE_CLOSERS.containsKey(currentOctave)) {
            rowText.append(OCTAVE_CLOSERS.get(currentOctave));
        }
    }

    // When a "1" is written as an "8" an octave lower, removes the bracket that already closed that lower octave
    static void octaveFixer(StringBuilder rowText, int currentOctave, int previousOctave, boolean isFirst) {
        if (isFirst) {
            previousOctave = 2;
        }

        if (currentOctave == 0 && previousOctave == -1) {
            int index = rowText.lastIndexOf("⟧⟧");
            if (index != -1) {
                rowText.delete(index, index + 2);
            }
        } else if (currentOctave == 1 && previousOctave == 0) {
            int index = rowText.lastIndexOf("⟧");
            if (index != -1) {
                rowText.deleteCharAt(index);
            }
        } else if (currentOctave == 2 && previousOctave == 1) {
            int index = rowText.lastIndexOf("]");
            if (index != -1) {
                rowText.deleteCharAt(index);
            }
        } else if (currentOctave == 4 && previousOctave == 3) {
            int index = rowText.lastIndexOf(")");
            if (index != -1) {
                rowText.deleteCharAt(index);
            }
        } else if (previousOctave == 3) {
            if (rowText.charAt(rowText.length() - 1) == ' ') {
                rowText.deleteCharAt(rowText.length() - 1);
                postOctaveHelper(rowText, 3, 2);
                rowText.append(' ');
            } else {
                postOctaveHelper(rowText, 3, 2);
            }
        } else if (currentOctave == 5 && previousOctave == 4) {
            int index = rowText.lastIndexOf("⸩");
            if (index != -1) {
                rowText.deleteCharAt(index);
            }
        } else if (currentOctave == 6 && previousOctave == 5) {
            int index = rowText.lastIndexOf(")⸩");
            if (index != -1) {
                rowText.delete(index, index + 2);
            }
        }
    }
}

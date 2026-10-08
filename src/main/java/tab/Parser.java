package tab;

import model.Box;
import model.Instrument;
import model.LineTiming;
import model.MusicElement;
import model.TimeSigMusicElement;
import model.TupletMusicElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import static tab.InstrumentKeys.getOctave;
import static tab.OctaveBrackets.postOctaveHelper;
import static tab.OctaveBrackets.preOctaveHelper;
import static tab.RhythmWriter.advance;

public class Parser {
    private final ArrayList<Box> boxes;
    private final ArrayList<Instrument> instruments;
    private double currentBeat = 1; // Beat always in quarter notes
    private int prevOctave = 2;
    private double quartersPerMeasure = 0.0;
    private int timeSigBeats = 0;
    private int timeSigNoteLength = 4;
    private final int measuresPerRow;
    private final TabWriter tabWriter;
    private final InstrumentKeys keys;
    private final TonicChooser tonicChooser;
    private final RhythmWriter rhythmWriter;

    // Notes each column can't play, which are written as "?"
    private final Map<Instrument, List<Integer>> unplayablePitches = new LinkedHashMap<>();

    public Parser(ArrayList<Instrument> instrumentList, ArrayList<Box> boxes, StyleSettings style, String sharpStyle, int measuresPerRow, String title) {
        this.measuresPerRow = measuresPerRow;
        this.boxes = boxes;
        this.instruments = instrumentList;
        this.tabWriter = new TabWriter(style);
        this.keys = new InstrumentKeys(sharpStyle, style);
        this.tonicChooser = new TonicChooser(instruments);
        this.rhythmWriter = new RhythmWriter(tabWriter);

        tabWriter.writeTitle(title);
        parseInstruments();
    }

    private void parseInstruments() {
        // Opening table tags
        tabWriter.write("<div dir=\"ltr\"><table><tbody>");
        tabWriter.writeHeader(instruments, boxes.size());

        // For each table row
        char currentLetter = 'A';
        int lineOffset = 0;
        for (int row = 0; row < boxes.size(); row++) {
            tabWriter.write("<tr>");
            tabWriter.writeRowLabel(boxes.size(), row + 1, currentLetter);
            // For each column
            double startingQuartersPerMeasure = quartersPerMeasure;
            int startingBeats = timeSigBeats;
            int startingNoteLength = timeSigNoteLength;
            for (int instrumentNum = 0; instrumentNum < instruments.size(); instrumentNum++) {
                tabWriter.writeBoxOpener(boxes.size(), row + 1);
                prevOctave = 2;
                // For each line
                for (int line = 0; line < boxes.get(row).getNumLines(measuresPerRow); line++) {
                    tabWriter.writeLineOpener();
                    tabWriter.write(parseLine(instruments.get(instrumentNum), lineOffset + line));
                    tabWriter.write("</span></p>");
                }
                // reset timeSig when starting next instrument
                if (startingQuartersPerMeasure != 0.0 && instrumentNum < instruments.size() - 1) {
                    quartersPerMeasure = startingQuartersPerMeasure;
                    timeSigBeats = startingBeats;
                    timeSigNoteLength = startingNoteLength;
                }
                tabWriter.write("</td>");
            }
            lineOffset += boxes.get(row).getNumLines(measuresPerRow);
            tabWriter.writeRowLabel(boxes.size(), row + 1, currentLetter);
            tabWriter.write("</tr>");
            currentLetter++;
        }
        tabWriter.writeTableCloser(instruments);
    }

    private String parseLine(Instrument instrument, int lineNum) {
        StringBuilder lineText = new StringBuilder();
        int currentOctave = 2;
        int nextOctave = 2;
        LineTiming timing = instrument.getLineTimings().get(lineNum);
        // Quarter notes from the start of the line; the beat comes from this so short measures are counted right
        double position = 0;
        currentBeat = timing.getBeat(position);
        double secondBeat = getSecondBeat();

        // Get elements for all measures in the row
        ArrayList<MusicElement> lineElements = instrument.getStaff().get(lineNum);
        if (lineElements.isEmpty()) {
            return "";
        }

        boolean firstNote = true;
        boolean inCurlyBrackets = false;
        for (int i = 0; i < lineElements.size(); i++) {
            MusicElement musicElement = lineElements.get(i);
            if (musicElement instanceof TimeSigMusicElement) {
                timeSigBeats = ((TimeSigMusicElement) musicElement).getBeats();
                timeSigNoteLength = ((TimeSigMusicElement) musicElement).getNoteLength();
                quartersPerMeasure = timeSigBeats * (4.0 / timeSigNoteLength);
                secondBeat = getSecondBeat();
                continue;
            }

            if (musicElement instanceof TupletMusicElement && ((TupletMusicElement) musicElement).isFirst()) {
                lineText.append(",<u>");
            }

            double duration = musicElement.getDuration();
            if (!musicElement.isRest()) {
                //32nd note or smaller (handled before the beat highlight so the tags nest properly)
                if (duration <= 0.125) {
                    if (!inCurlyBrackets) {
                        lineText.append("<em style=\"font-family: 'Courier New'; font-style: italic;\">{");
                        inCurlyBrackets = true;
                    }
                } else if (duration > 0.125) {
                    if (inCurlyBrackets) {
                        lineText.append("}</em>");
                        inCurlyBrackets = false;
                    }
                }

                String beatCloser = "";
                if (currentBeat == 1) {
                    lineText.append(tabWriter.writePrimaryBeatOpener());
                    beatCloser = tabWriter.writePrimaryBeatCloser();
                } else if (currentBeat == secondBeat) {
                    lineText.append(tabWriter.writeSecondaryBeatOpener());
                    beatCloser = tabWriter.writeSecondaryBeatCloser();
                }

                ArrayList<Integer> noteList = musicElement.getNoteList();

                // Arpeggios within one octave are written in braces
                boolean bracketArpeggio = musicElement.getArpeggioType() != 0 && noteList.size() > 2
                        && getOctave(noteList.get(0), instrument.getName()) == getOctave(noteList.get(noteList.size() - 1), instrument.getName());
                if (bracketArpeggio) {
                    lineText.append("{");
                }

                // For each note
                for (int k = 0; k < noteList.size(); k++) {
                    int pitch = noteList.get(k);
                    currentOctave = getOctave(pitch, instrument.getName());
                    String convertedPitch = keys.getKey(pitch, instrument.getName());
                    if (convertedPitch.equals("?")) {
                        unplayablePitches.computeIfAbsent(instrument, key -> new ArrayList<>()).add(pitch);
                    }
                    if (convertedPitch.equals("1")) {
                        convertedPitch = tonicChooser.choose(lineText, instrument, musicElement, k, prevOctave);
                        if (convertedPitch.equals("8")) {
                            currentOctave--;
                        }
                    }

                    if (firstNote) {
                        preOctaveHelper(lineText, 2, currentOctave);
                    } else {
                        preOctaveHelper(lineText, prevOctave, currentOctave);
                    }
                    prevOctave = currentOctave;

                    lineText.append(convertedPitch);

                    // If not final note of chord, close this note's octave if the next one is in another
                    if (k < noteList.size() - 1) {
                        nextOctave = getOctave(noteList.get(k + 1), instrument.getName());
                        postOctaveHelper(lineText, currentOctave, nextOctave);
                        lineText.append("/");
                    }
                    firstNote = false;
                }

                if (bracketArpeggio) {
                    lineText.append("}");
                }

                lineText.append(beatCloser);

            } else if (musicElement.isRest() && musicElement.getDuration() != 0 && firstNote) {
                prevOctave = 2;
                firstNote = false;
            }

            // Tuplet notes are written at their normal length but take less (or more) time
            double timeScale = musicElement instanceof TupletMusicElement ? ((TupletMusicElement) musicElement).getTimeScale() : 1;
            boolean addSpace = duration >= 0.5;
            lineText.append(rhythmWriter.write(duration, !musicElement.isRest(), timing, position, quartersPerMeasure, secondBeat, timeScale));

            position = advance(position, duration, timeScale);
            currentBeat = timing.getBeat(position);

            // Next element that isn't a time signature, or -1 if this is the last one in the line
            int nextIndex = getNextNonTimeSigIndex(lineElements, i);

            // Find next Chord in line if there is one
            MusicElement nextChord = null;
            int currentIndex = i + 1;
            while (currentIndex < lineElements.size()) {
                MusicElement nextElement = lineElements.get(currentIndex);
                if (!nextElement.isRest()) {
                    nextChord = nextElement;
                    break;
                }
                currentIndex++;
            }

            // Set next octave based on first note of next chord
            if (nextChord != null) {
                nextOctave = getOctave(nextChord.getNoteList().get(0), instrument.getName());
            } else {
                nextOctave = 2;
            }

            // Don't add space if eighth note rest.
            if (duration <= 0.5 && musicElement.isRest()) {
                addSpace = false;
            }

            if (nextIndex != -1 && !lineElements.get(nextIndex).isRest()) {
                postOctaveHelper(lineText, currentOctave, nextOctave);
            }
            // Highlight the space when an eighth note crosses onto a strong beat
            String spaceCloser = "";
            if (duration == 0.5 && currentBeat == 1.25) {
                lineText.append(tabWriter.writePrimaryBeatOpener());
                spaceCloser = tabWriter.writePrimaryBeatCloser();
            } else if (duration == 0.5 && currentBeat == secondBeat + 0.25) {
                lineText.append(tabWriter.writeSecondaryBeatOpener());
                spaceCloser = tabWriter.writeSecondaryBeatCloser();
            }
            if (addSpace) {
                lineText.append(' ');
            }
            lineText.append(spaceCloser);
            if (nextIndex == -1) {
                postOctaveHelper(lineText, currentOctave, nextOctave);
            }

            if (duration <= 0.25 && nextIndex != -1 && (lineElements.get(nextIndex).getNoteList().size() > 1 || musicElement.getNoteList().size() > 1)) {
                lineText.append("`");
            }

            if (musicElement instanceof TupletMusicElement && ((TupletMusicElement) musicElement).isLast()) {
                lineText.append("</u>,");
            }

        }

        if (inCurlyBrackets) {
            lineText.append("}</em>");
        }
        MusicElement lastElement = lineElements.get(lineElements.size() - 1);
        double lastDuration = lastElement.getDuration();
        if (lastDuration <= 0.25) {
            lineText.append('`');
        }

        String rowString = lineText.toString();
        instrument.updateColumnWidth(getTextWidth(rowString.replaceAll("<[^>]*>", "")));

        return rowString;
    }

    private int getNextNonTimeSigIndex(ArrayList<MusicElement> lineElements, int index) {
        for (int i = index + 1; i < lineElements.size(); i++) {
            if (!(lineElements.get(i) instanceof TimeSigMusicElement)) {
                return i;
            }
        }
        return -1;
    }

    // Beat where the secondary highlight goes, or 999 if the time signature has none
    private double getSecondBeat() {
        if (quartersPerMeasure == 2) {
            return 2;
        } else if (quartersPerMeasure == 4) {
            return 3;
        } else if (quartersPerMeasure == 6) {
            return 4;
        } else if (quartersPerMeasure == 12) {
            return 7;
        } else if (timeSigBeats == 6 && timeSigNoteLength == 8) {
            // 6/8 is two dotted quarter beats
            return 2.5;
        } else {
            return 999;
        }
    }

    public void copyToClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putHtml(tabWriter.getTab());
        clipboard.setContent(clipboardContent);
    }

    public String getTab() {
        return tabWriter.getTab();
    }

    // Problems the user should know about, like notes that were written as "?"
    public List<String> getWarnings() {
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<Instrument, List<Integer>> entry : unplayablePitches.entrySet()) {
            Set<String> noteNames = new LinkedHashSet<>();
            for (int pitch : new TreeSet<>(entry.getValue())) {
                noteNames.add(InstrumentKeys.noteName(pitch));
            }
            warnings.add(getColumnName(entry.getKey()) + ": " + entry.getValue().size()
                    + " note(s) have no key on this instrument and are written as ? (" + String.join(", ", noteNames) + ")");
        }
        return warnings;
    }

    // The instrument's column heading, numbered like the table header when names repeat
    private String getColumnName(Instrument instrument) {
        int count = 0;
        int number = 0;
        for (Instrument other : instruments) {
            if (other.getName().equals(instrument.getName())) {
                count++;
                if (other == instrument) {
                    number = count;
                }
            }
        }
        return count > 1 ? instrument.getName() + " " + number : instrument.getName();
    }

    private double getTextWidth(String text) {
        double textWidth = 30;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            switch (character) {
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '⦓':
                case '⦔':
                    textWidth += 7.7;
                    break;
                case '[':
                case ']':
                case '(':
                case ')':
                case '|':
                case '-':
                case '`':
                    textWidth += 4.7;
                    break;
                case '/':
                case '.':
                case ',':
                    textWidth += 4;
                    break;
                case '1':
                    textWidth += 7;
                    break;
                case '~':
                case '#':
                case 'F':
                    textWidth += 8;
                    break;
                case ' ':
                    textWidth += 4.7;
                    break;
                case '{':
                case '}':
                    textWidth += 5.5;
                    break;
                default:

                    break;
            }
        }
        return textWidth;
    }
}

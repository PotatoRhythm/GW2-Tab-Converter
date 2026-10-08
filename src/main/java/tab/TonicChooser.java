package tab;

import model.Instrument;
import model.MusicElement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static tab.InstrumentKeys.getOctave;
import static tab.OctaveBrackets.octaveFixer;

// Pick optimal 1 or 8 to to maximize time for octave swapping
class TonicChooser {
    // Every element of a staff in order, with each element's position and line
    private static class StaffIndex {
        final List<MusicElement> elements = new ArrayList<>();
        final Map<MusicElement, Integer> positions = new IdentityHashMap<>();
        final Map<MusicElement, ArrayList<MusicElement>> lines = new IdentityHashMap<>();

        StaffIndex(Instrument instrument) {
            for (ArrayList<MusicElement> line : instrument.getStaff()) {
                for (MusicElement element : line) {
                    positions.put(element, elements.size());
                    lines.put(element, line);
                    elements.add(element);
                }
            }
        }
    }

    // Lookups into each instrument's whole staff, used to compare a note with its neighbours
    private final Map<Instrument, StaffIndex> staffIndexes = new IdentityHashMap<>();

    TonicChooser(List<Instrument> instruments) {
        for (Instrument instrument : instruments) {
            staffIndexes.put(instrument, new StaffIndex(instrument));
        }
    }

    // Decides whether a "1" is written as "1" or as "8" in the octave below, whichever needs fewer octave changes.
    // prevOctave is the octave of the note written before it
    String choose(StringBuilder lineText, Instrument instrument, MusicElement currentElement, int noteIndex, int prevOctave) {
        ArrayList<Integer> noteList = currentElement.getNoteList();
        //  Get current note info
        int currentPitch = noteList.get(noteIndex);
        int currentOctave = getOctave(currentPitch, instrument.getName());
        double currentDuration = getExtendedDuration(instrument, currentElement);
        boolean isFirst = false;
        MusicElement firstElement = null;
        for (MusicElement musicElement : getLine(instrument, currentElement)) {
            if (musicElement.getDuration() != 0) {
                firstElement = musicElement;
                break;
            }
        }
        if (currentElement == firstElement && noteIndex == 0) {
            isFirst = true;
        }

        // Get previous note info
        MusicElement prevChord = getPreviousChord(instrument, currentElement);
        double prevDuration = prevChord != null ? getExtendedDuration(instrument, prevChord) : 999;

        // Get next note info
        MusicElement nextChord = getNextChord(instrument, currentElement);
        int nextPitch;
        int nextOctave;
        if (noteList.size() > 1 && noteIndex != noteList.size() - 1) {
            nextPitch = noteList.get(noteIndex + 1);
            nextOctave = getOctave(nextPitch, instrument.getName());
        }
        else if (nextChord != null) {
            nextPitch = nextChord.getNoteList().get(0);
            nextOctave = getOctave(nextPitch, instrument.getName());
        } else {
            nextPitch = 0;
            nextOctave = 2;
        }

        // Minimize octave swaps within chords
        if (noteList.size() > 1) {
            if (noteIndex != 0) {
                if (prevOctave < currentOctave && noteIndex == noteList.size() - 1) {
                    octaveFixer(lineText, currentOctave, prevOctave, isFirst);
                    return "8";
                }
            } else {
                if (nextOctave < currentOctave) {
                    octaveFixer(lineText, currentOctave, prevOctave, isFirst);
                    return "8";
                }
            }
            return "1";
        }

        //Find following 1 with the longest duration and compare its duration to previous chord
        if (nextPitch == currentPitch) {
            double longestDuration = currentDuration;
            MusicElement nextElement = getNextChord(instrument, currentElement);
            while (nextPitch == currentPitch) {
                if (nextElement.getNoteList().size() > 1) {
                    break;
                }
                else if (getExtendedDuration(instrument, nextElement) >= longestDuration) {
                    longestDuration = getExtendedDuration(instrument, nextElement);
                }
                nextElement = getNextChord(instrument, nextElement);
                if (nextElement != null) {
                    nextPitch = nextElement.getNoteList().get(0);
                } else {
                    nextPitch = -1;
                }

            }
            if (longestDuration >= prevDuration) {
                if (prevOctave < currentOctave) {
                    octaveFixer(lineText, currentOctave, prevOctave, isFirst);
                    return "8";
                } else {
                    return "1";
                }
            } else {
                if (getOctave(nextPitch, instrument.getName()) < currentOctave) {
                    octaveFixer(lineText, currentOctave, getOctave(nextPitch, instrument.getName()), isFirst);
                    return "8";
                } else {
                    return "1";
                }
            }
        }

        if (prevDuration < currentDuration && prevOctave > currentOctave) {
            return "1";
        } else if (prevDuration < currentDuration && prevOctave < currentOctave) {
            octaveFixer(lineText, currentOctave, prevOctave, isFirst);
            return "8";
        } else if (prevDuration > currentDuration && nextOctave > currentOctave) {
            return "1";
        } else if (prevDuration > currentDuration && nextOctave < currentOctave) {
            octaveFixer(lineText, currentOctave, prevOctave, isFirst);
            return "8";
        } else if (prevDuration == currentDuration) {
            if (isFirst) {
                if (nextOctave > currentOctave) {
                    return "1";
                } else if (nextOctave < currentOctave) {
                    octaveFixer(lineText, currentOctave, prevOctave, true);
                    return "8";
                }
            } else {
                if (prevOctave > currentOctave) {
                    return "1";
                } else if (prevOctave < currentOctave) {
                    octaveFixer(lineText, currentOctave, prevOctave, false);
                    return "8";
                }
            }
        }

        return "1";
    }

    private ArrayList<MusicElement> getLine(Instrument instrument, MusicElement musicElement) {
        return staffIndexes.get(instrument).lines.get(musicElement);
    }

    // Duration of an element plus any rests after it
    private double getExtendedDuration(Instrument instrument, MusicElement musicElement) {
        StaffIndex index = staffIndexes.get(instrument);
        double duration = musicElement.getDuration();
        for (int i = index.positions.get(musicElement) + 1; i < index.elements.size(); i++) {
            MusicElement current = index.elements.get(i);
            if (!current.isRest()) {
                break;
            }
            duration += current.getDuration();
        }
        return duration;
    }

    // First element with notes after the given one, or null
    private MusicElement getNextChord(Instrument instrument, MusicElement musicElement) {
        StaffIndex index = staffIndexes.get(instrument);
        for (int i = index.positions.get(musicElement) + 1; i < index.elements.size(); i++) {
            if (!index.elements.get(i).isRest()) {
                return index.elements.get(i);
            }
        }
        return null;
    }

    // Last element with notes before the given one, or null
    private MusicElement getPreviousChord(Instrument instrument, MusicElement musicElement) {
        StaffIndex index = staffIndexes.get(instrument);
        for (int i = index.positions.get(musicElement) - 1; i >= 0; i--) {
            if (!index.elements.get(i).isRest()) {
                return index.elements.get(i);
            }
        }
        return null;
    }
}

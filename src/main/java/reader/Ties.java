package reader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.util.ArrayList;
import java.util.List;

import static reader.ScoreXml.*;

// Joins tied notes, so the first note lasts the whole length
final class Ties {
    private Ties() {
    }

    static void preprocessTies(Element staff) {
        // Handle each voice separately
        for (List<Element> chordList : getChordsByVoice(staff)) {
            preprocessTies(staff, chordList);
        }
    }

    private static void preprocessTies(Element staff, List<Element> chordList) {
        // Iterate through chordList
        for (int i = 0; i < chordList.size(); i++) {
            Element startChord = chordList.get(i);
            List<Integer> startNotes = new ArrayList<>();
            NodeList notes = startChord.getElementsByTagName("Note");
            // Iterate through notes and add any start notes to startNotes list
            for (int j = 0; j < notes.getLength(); j++) {
                Element note = (Element) notes.item(j);
                // If start tie, add to startNotes list
                if (hasTieTo(note, "next") && !hasTieTo(note, "prev")) {
                    startNotes.add(getPitch(note));
                }
            }
            int currentIndex = i + 1;
            // While startNotes list is not empty, iterate through subsequent chords and process ties that correspond to startNotes
            while (!startNotes.isEmpty() && currentIndex < chordList.size()) {
                Element currentChord = chordList.get(currentIndex);
                boolean canMerge = true;
                // List to store notes in the current chord that correspond to startNotes
                List<Element> correspondingTies = new ArrayList<>();
                // Iterate through notes in the current chord to check corresponding ties
                NodeList currentChordNotes = currentChord.getElementsByTagName("Note");
                for (int k = 0; k < currentChordNotes.getLength(); k++) {
                    Element currentNote = (Element) currentChordNotes.item(k);
                    int pitch = getPitch(currentNote);
                    // If it's a middle or end tie of one of the start notes
                    if (hasTieTo(currentNote, "prev") && startNotes.contains(pitch)) {
                        correspondingTies.add(currentNote);
                        // If end tie
                        if (!hasTieTo(currentNote, "next")) {
                            startNotes.remove(Integer.valueOf(pitch));
                        }
                    } else {
                        canMerge = false;
                    }
                }
                if (canMerge) {
                    // Every note is a tie continuation, so the whole chord becomes a rest
                    replaceWithRest(staff, currentChord);
                } else {
                    // Remove notes with middle or end ties corresponding to start ties
                    for (Element tiedNote : correspondingTies) {
                        tiedNote.getParentNode().removeChild(tiedNote);
                    }
                    // if currentChord no longer contains any notes
                    if (currentChord.getElementsByTagName("Note").getLength() == 0) {
                        replaceWithRest(staff, currentChord);
                    }
                }
                currentIndex++;
            }
        }
    }

    // Returns true if the note has a tie whose other end is in the given direction ("prev" or "next")
    private static boolean hasTieTo(Element note, String direction) {
        NodeList spanners = note.getElementsByTagName("Spanner");
        for (int i = 0; i < spanners.getLength(); i++) {
            Element spanner = (Element) spanners.item(i);
            if (spanner.getAttribute("type").equals("Tie") && spanner.getElementsByTagName(direction).getLength() != 0) {
                return true;
            }
        }
        return false;
    }

    private static void replaceWithRest(Element staff, Element chord) {
        Document doc = staff.getOwnerDocument();
        Element rest = doc.createElement("Rest");

        Element durationType = doc.createElement("durationType");
        durationType.setTextContent(chord.getElementsByTagName("durationType").item(0).getTextContent());
        rest.appendChild(durationType);

        Element dotsElement = (Element) chord.getElementsByTagName("dots").item(0);
        if (dotsElement != null) {
            Element dotValue = doc.createElement("dots");
            dotValue.setTextContent(dotsElement.getTextContent());
            rest.appendChild(dotValue);
        }

        chord.getParentNode().replaceChild(rest, chord);
    }

    // Returns one chord list per voice, in score order
    private static List<List<Element>> getChordsByVoice(Element staff) {
        List<List<Element>> chordsByVoice = new ArrayList<>();
        for (Element measure : getMeasures(staff)) {
            List<Element> voices = getChildElements(measure, "voice");
            for (int v = 0; v < voices.size(); v++) {
                if (chordsByVoice.size() <= v) {
                    chordsByVoice.add(new ArrayList<>());
                }
                chordsByVoice.get(v).addAll(getChildElements(voices.get(v), "Chord"));
            }
        }
        return chordsByVoice;
    }
}

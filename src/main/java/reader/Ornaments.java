package reader;

import org.w3c.dom.Element;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import static reader.ScoreXml.*;

// Moves grace notes, trills and tremolos onto the chords they belong to, stored as attributes,
// so voice merging and the rest of the converter only see ordinary chords
public final class Ornaments {
    private Ornaments() {
    }

    // Grace chords played before / after the chord: pitches joined by "," and chords by ";"
    public static final String GRACE_BEFORE = "gw2GraceBefore";
    static final String GRACE_AFTER = "gw2GraceAfter";
    // Extra pitches written inside tr{ }: the trill's upper note, or the other chord of a two-chord tremolo
    public static final String ORNAMENT = "gw2Ornament";

    private static final List<String> GRACE_AFTER_TAGS = List.of("grace8after", "grace16after", "grace32after");

    static void preprocessOrnaments(Element staff, List<String> warnings) {
        List<Element> measures = getMeasures(staff);
        int leftOut = 0;
        for (Element measure : measures) {
            for (Element voice : getChildElements(measure, "voice")) {
                leftOut += attachGraceNotes(voice);
                attachTremolos(voice);
            }
        }
        if (leftOut > 0) {
            warnings.add("Staff " + staff.getAttribute("id") + ": " + leftOut + " grace note(s) with no main note were left out.");
        }
        markTrills(staff);
    }

    // Removes grace chords from the voice and stores their pitches on the chord they belong to.
    // MuseScore writes all of a chord's grace notes, including the ones played after it, just before it.
    // Returns the number of grace notes left out because no chord follows them
    private static int attachGraceNotes(Element voice) {
        List<Element> pending = new ArrayList<>();
        for (Element child : getChildElementsList(voice)) {
            if (!child.getTagName().equals("Chord")) {
                continue;
            }
            if (isGrace(child)) {
                pending.add(child);
                continue;
            }
            List<String> before = new ArrayList<>();
            List<String> after = new ArrayList<>();
            for (Element grace : pending) {
                boolean isAfter = getChildElementsList(grace).stream().anyMatch(e -> GRACE_AFTER_TAGS.contains(e.getTagName()));
                (isAfter ? after : before).add(joinPitches(grace));
                voice.removeChild(grace);
            }
            appendGraceAttribute(child, GRACE_BEFORE, before);
            appendGraceAttribute(child, GRACE_AFTER, after);
            pending.clear();
        }
        int leftOut = 0;
        for (Element grace : pending) {
            leftOut += getChildElements(grace, "Note").size();
            voice.removeChild(grace);
        }
        return leftOut;
    }

    private static void appendGraceAttribute(Element chord, String attribute, List<String> graceChords) {
        if (graceChords.isEmpty()) {
            return;
        }
        String joined = String.join(";", graceChords);
        String existing = chord.getAttribute(attribute);
        chord.setAttribute(attribute, existing.isEmpty() ? joined : existing + ";" + joined);
    }

    private static String joinPitches(Element chord) {
        return getChildElements(chord, "Note").stream()
                .map(note -> Integer.toString(getPitch(note)))
                .collect(Collectors.joining(","));
    }

    // A one-chord tremolo is written tr{1}. A two-chord tremolo is written tr{1/3}: the second chord is removed
    // and the first takes both of their time (MuseScore gives each half of it with a <duration>)
    private static void attachTremolos(Element voice) {
        List<Element> chords = getChildElements(voice, "Chord");
        for (int i = 0; i < chords.size(); i++) {
            Element chord = chords.get(i);
            Element tremolo = getChildElementsList(chord).stream()
                    .filter(e -> e.getTagName().startsWith("Tremolo"))
                    .findFirst().orElse(null);
            if (tremolo == null || chord.getParentNode() == null) {
                continue;
            }
            Element subtype = (Element) tremolo.getElementsByTagName("subtype").item(0);
            boolean twoChord = tremolo.getTagName().equals("TremoloTwoChord")
                    || (subtype != null && subtype.getTextContent().trim().startsWith("c"));
            String otherPitches = "";
            if (twoChord && i + 1 < chords.size()) {
                Element second = chords.get(i + 1);
                otherPitches = joinPitches(second);
                voice.removeChild(second);
                for (Element duration : getChildElements(chord, "duration")) {
                    chord.removeChild(duration);
                }
                i++;
            }
            chord.setAttribute(ORNAMENT, otherPitches);
        }
    }

    // Marks chords with a trill ornament, or under a trill line, with the note above their highest note
    private static void markTrills(Element staff) {
        List<Element> measures = getMeasures(staff);
        List<Double> measureLengths = getMeasureLengths(staff);
        List<double[]> starts = new ArrayList<>(); // {time, voice}
        List<double[]> ends = new ArrayList<>();
        List<VoiceEvent> chords = new ArrayList<>();
        List<Integer> chordVoices = new ArrayList<>();
        List<Integer> chordKeys = new ArrayList<>();
        int key = 0;
        double measureStart = 0;
        for (int m = 0; m < measures.size(); m++) {
            List<Element> voices = getChildElements(measures.get(m), "voice");
            for (int v = 0; v < voices.size(); v++) {
                for (Element keySig : getChildElements(voices.get(v), "KeySig")) {
                    key = getKey(keySig, key);
                }
                for (VoiceEvent event : getTimedElements(voices.get(v), measureLengths.get(m))) {
                    Element element = event.element;
                    double time = measureStart + event.onset;
                    if (element.getTagName().equals("Chord")) {
                        if (hasTrillOrnament(element)) {
                            markTrill(element, key);
                        }
                        chords.add(new VoiceEvent(element, time, event.length, -1));
                        chordVoices.add(v);
                        chordKeys.add(key);
                    } else if (element.getTagName().equals("Spanner") && element.getAttribute("type").equals("Trill")) {
                        if (!getChildElements(element, "next").isEmpty()) {
                            starts.add(new double[]{time, v});
                        } else if (!getChildElements(element, "prev").isEmpty()) {
                            ends.add(new double[]{time, v});
                        }
                    }
                }
            }
            measureStart += measureLengths.get(m);
        }

        starts.sort(Comparator.comparingDouble(start -> start[0]));
        ends.sort(Comparator.comparingDouble(end -> end[0]));
        for (double[] start : starts) {
            // Each line ends at the first unused end after its start in the same voice
            double end = Double.MAX_VALUE;
            for (Iterator<double[]> it = ends.iterator(); it.hasNext(); ) {
                double[] candidate = it.next();
                if (candidate[1] == start[1] && candidate[0] > start[0] + EPSILON) {
                    end = candidate[0];
                    it.remove();
                    break;
                }
            }
            for (int i = 0; i < chords.size(); i++) {
                VoiceEvent chord = chords.get(i);
                if (chordVoices.get(i) == start[1] && chord.length > 0
                        && chord.onset >= start[0] - EPSILON && chord.onset < end - EPSILON) {
                    markTrill(chord.element, chordKeys.get(i));
                }
            }
        }
    }

    // Trills and short trills (pralls); mordents and turns are left as plain notes
    private static boolean hasTrillOrnament(Element chord) {
        for (Element child : getChildElementsList(chord)) {
            if (!child.getTagName().equals("Articulation") && !child.getTagName().equals("Ornament")) {
                continue;
            }
            Element subtype = (Element) child.getElementsByTagName("subtype").item(0);
            String name = subtype == null ? "" : subtype.getTextContent().trim().toLowerCase();
            if (name.contains("trill") || name.contains("prall") || name.contains("tremblement")) {
                return true;
            }
        }
        return false;
    }

    private static void markTrill(Element chord, int key) {
        if (chord.hasAttribute(ORNAMENT)) {
            return;
        }
        Element top = getChildElements(chord, "Note").stream()
                .max(Comparator.comparingInt(ScoreXml::getPitch)).orElse(null);
        if (top != null) {
            chord.setAttribute(ORNAMENT, Integer.toString(getUpperNote(top, key)));
        }
    }

    // Sharps (positive) or flats (negative) in a key signature
    private static int getKey(Element keySig, int previousKey) {
        for (String tag : new String[]{"concertKey", "accidental"}) {
            Element element = (Element) keySig.getElementsByTagName(tag).item(0);
            if (element != null) {
                try {
                    return Integer.parseInt(element.getTextContent().trim());
                } catch (NumberFormatException e) {
                    return previousKey;
                }
            }
        }
        return previousKey;
    }

    // The next note up the scale of the key: the following letter name, with the key signature's sharp or flat.
    // Uses the note's spelling (tpc: position on the circle of fifths, C = 14), or a whole step if it has none
    private static int getUpperNote(Element note, int key) {
        int pitch = getPitch(note);
        Element tpcElement = (Element) note.getElementsByTagName("tpc").item(0);
        if (tpcElement == null) {
            return pitch + 2;
        }
        int tpc = Integer.parseInt(tpcElement.getTextContent().trim());
        // Letters in fifths order (F C G D A E B) by (tpc + 1) % 7, as positions in C D E F G A B
        int[] letterOfFifth = {3, 0, 4, 1, 5, 2, 6};
        int[] naturalTpc = {14, 16, 18, 13, 15, 17, 19}; // C D E F G A B
        int letter = letterOfFifth[Math.floorMod(tpc + 1, 7)];
        int upperTpc = naturalTpc[(letter + 1) % 7];
        // Key with this many sharps uses the tpcs from 13 + key to 19 + key
        while (upperTpc < 13 + key) {
            upperTpc += 7;
        }
        while (upperTpc > 19 + key) {
            upperTpc -= 7;
        }
        int step = Math.floorMod(tpcToPitchClass(upperTpc) - tpcToPitchClass(tpc), 12);
        return pitch + (step == 0 ? 12 : step);
    }

    private static int tpcToPitchClass(int tpc) {
        return Math.floorMod(7 * (tpc - 14), 12);
    }

    // Copies grace notes and ornaments from chords that were merged into another chord
    static void copyOrnaments(Element target, List<Element> sources) {
        for (Element source : sources) {
            for (String attribute : new String[]{GRACE_BEFORE, GRACE_AFTER}) {
                if (source.hasAttribute(attribute) && source != target) {
                    appendGraceAttribute(target, attribute, List.of(source.getAttribute(attribute)));
                }
            }
            if (source.hasAttribute(ORNAMENT) && !target.hasAttribute(ORNAMENT)) {
                target.setAttribute(ORNAMENT, source.getAttribute(ORNAMENT));
            }
        }
    }
}

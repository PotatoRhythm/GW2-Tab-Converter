package reader;

import model.Box;
import model.Instrument;
import reader.midi.MidiReader;
import model.LineTiming;
import model.MusicElement;
import model.TimeSigMusicElement;
import model.TupletMusicElement;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static reader.ScoreXml.*;

public class Preprocessor {
    private final ArrayList<Instrument> instrumentList;
    private final ArrayList<Box> boxes;
    private String title;
    private final int measuresPerRow;
    private final boolean mergeStaves;
    private final boolean approximateTiming;
    private final List<String> warnings = new ArrayList<>();
    // Notes that can't be placed exactly and are left out or aproximated 
    private final List<String> droppedNotes = new ArrayList<>();
    private boolean hasExtraMeasures = false;
    private final VoiceMerger voiceMerger;

    public Preprocessor(File musescoreFile, int measuresPerRow, boolean mergeStaves, boolean approximateTiming) throws Exception {
        // No title in the score, so use the file name
        this(readScore(musescoreFile), musescoreFile.getName().replaceFirst("\\.[^.]*$", "").replace('_', ' ').trim(),
                measuresPerRow, mergeStaves, approximateTiming);
    }

    // Reads a score that's already loaded, like one built from a MIDI file. fallbackTitle is used if it has no title
    public Preprocessor(Document doc, String fallbackTitle, int measuresPerRow, boolean mergeStaves, boolean approximateTiming) {
        this.measuresPerRow = measuresPerRow;
        this.mergeStaves = mergeStaves;
        this.approximateTiming = approximateTiming;
        this.voiceMerger = new VoiceMerger(approximateTiming, droppedNotes);
        this.instrumentList = new ArrayList<>();
        this.boxes = new ArrayList<>();
        warnAboutUnsupportedDurations(doc);
        parseTitle(doc);
        if (title == null || title.isEmpty()) {
            title = fallbackTitle;
        }
        processInstruments(doc);
    }

    // Reads a MuseScore file
    private static Document readScore(File musescoreFile) throws Exception {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        if (!musescoreFile.getName().toLowerCase().endsWith(".mscz")) {
            return builder.parse(musescoreFile);
        }
        try (ZipFile zip = new ZipFile(musescoreFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // The main score is at the top level; scores for individual parts are in a subfolder
                if (entry.getName().endsWith(".mscx") && !entry.getName().contains("/")) {
                    try (InputStream score = zip.getInputStream(entry)) {
                        return builder.parse(score);
                    }
                }
            }
        }
        throw new IllegalArgumentException("No score (.mscx) was found inside " + musescoreFile.getName() + ".");
    }

    private void parseTitle(Document doc) {
        NodeList metaTags = doc.getElementsByTagName("metaTag");
        for (int i = 0; i < metaTags.getLength(); i++) {
            Element metaTag = (Element) metaTags.item(i);
            String nameAttribute = metaTag.getAttribute("name");
            if (nameAttribute.equals("workTitle")) {
                title = metaTag.getTextContent().trim();
            }
        }
    }

    private void processInstruments(Document doc) {
        // Store lists for parts and staves. They're copied out of the live lists first: a live list searches the
        // whole score again after every change, which takes seconds for scores with many staves
        List<Element> parts = toList(doc.getElementsByTagName("Part"));
        List<Element> allStaves = new ArrayList<>();
        for (Element staff : toList(doc.getElementsByTagName("Staff"))) {
            Node parent = staff.getParentNode();
            if (parent == null || !parent.getNodeName().equals("Part")) {
                if (isGeneralDrums(findPart(parts, staff))) {
                    mapDrums(staff);
                }
                Ties.preprocessTies(staff);
                Ottavas.preprocessOttavas(staff, warnings);
                Ornaments.preprocessOrnaments(staff, warnings);
                allStaves.add(staff);
            }
        }
        if (allStaves.isEmpty() || getMeasures(allStaves.get(0)).isEmpty()) {
            throw new IllegalArgumentException("No staves with measures were found. Is this an uncompressed MuseScore (.mscx) file?");
        }

        // Each remaining staff becomes one column. With mergeStaves, the other staves of a part
        // (like a piano's left hand) are moved into the part's first staff
        Map<Element, Element> staffParts = new LinkedHashMap<>();
        Map<Element, Element> firstStaffOfPart = new LinkedHashMap<>();
        for (Element staff : allStaves) {
            Element part = findPart(parts, staff);
            if (mergeStaves && firstStaffOfPart.containsKey(part)) {
                mergeStaffInto(firstStaffOfPart.get(part), staff);
            } else {
                firstStaffOfPart.putIfAbsent(part, staff);
                staffParts.put(staff, part);
            }
        }
        List<Element> outputStaves = new ArrayList<>(staffParts.keySet());
        for (Element staff : outputStaves) {
            voiceMerger.mergeVoices(staff);
        }

        int measureCount = outputStaves.stream().mapToInt(staff -> getMeasures(staff).size()).max().orElse(0);
        for (Element staff : outputStaves) {
            padMeasures(staff, measureCount);
        }
        initBoxes(outputStaves.get(0));
        labelSwingBoxes(allStaves);
        markTempoAndTimeSignatures(allStaves);

        // Initialize Instruments
        for (Map.Entry<Element, Element> entry : staffParts.entrySet()) {
            Element stave = entry.getKey();
            Element part = entry.getValue();

            String instrumentName = ((Element) part.getElementsByTagName("Instrument").item(0)).getAttribute("id");
            if (instrumentName.startsWith("gw2")) {
                instrumentName = instrumentName.substring(3);
            }
            instrumentName = Character.toUpperCase(instrumentName.charAt(0)) + instrumentName.substring(1);
            if (instrumentName.equals("Choirbell")) {
                instrumentName = "Choir Bell";
            } else if (instrumentName.equals("Framedrum")) {
                instrumentName = "Frame Drum";
            }

            List<Double> measureLengths = getMeasureLengths(stave);
            List<Double> timeSigLengths = getTimeSigLengths(stave);
            // Found once per staff, not for every line, which is slow when there are many measures
            List<Element> voices = getFirstVoices(stave);
            int startMeasure = 0;
            ArrayList<ArrayList<MusicElement>> lines = new ArrayList<>();
            ArrayList<LineTiming> lineTimings = new ArrayList<>();
            for (Box box : boxes) {
                int remainingMeasures = box.getNumMeasures();
                for (int line = 0; line < box.getNumLines(measuresPerRow); line++) {
                    int lineMeasures = Math.min(remainingMeasures, measuresPerRow);
                    lines.add(processLine(voices, startMeasure, lineMeasures, measureLengths));
                    lineTimings.add(getLineTiming(measureLengths, timeSigLengths, startMeasure, lineMeasures));
                    startMeasure += lineMeasures;
                    remainingMeasures -= measuresPerRow;
                }
            }
            // Parts built from MIDI files are named after their MIDI instrument, but written with the keys above
            String columnName = part.getAttribute(MidiReader.COLUMN_NAME);
            String keys = isGeneralDrums(part) ? DrumKit.KEYS : instrumentName;
            instrumentList.add(new Instrument(columnName.isEmpty() ? instrumentName : columnName, keys, lines, lineTimings));
        }
    }

    // Measure lengths for a line of the tab, and the beat it starts on
    private LineTiming getLineTiming(List<Double> measureLengths, List<Double> timeSigLengths, int startMeasure, int numMeasures) {
        double[] lengths = new double[numMeasures];
        for (int i = 0; i < numMeasures; i++) {
            lengths[i] = measureLengths.get(startMeasure + i);
        }
        // A short first measure of the piece is a pickup: its notes are the last beats of a full measure
        double firstBeat = 1;
        if (startMeasure == 0 && numMeasures > 0 && lengths[0] < timeSigLengths.get(0) - EPSILON) {
            firstBeat = 1 + timeSigLengths.get(0) - lengths[0];
        }
        return new LineTiming(firstBeat, lengths);
    }

    // Note values the converter doesn't know are treated as taking no time
    private void warnAboutUnsupportedDurations(Document doc) {
        Set<String> unsupported = new LinkedHashSet<>();
        NodeList durationTypes = doc.getElementsByTagName("durationType");
        for (int i = 0; i < durationTypes.getLength(); i++) {
            String duration = durationTypes.item(i).getTextContent().trim();
            if (!duration.equals("measure") && !isSupportedDuration(duration)) {
                unsupported.add(duration);
            }
        }
        for (String duration : unsupported) {
            warnings.add("Unsupported note length \"" + duration + "\" was treated as no time.");
        }
    }

    // A MuseScore drum part that isn't already written for GW2's Drum or Frame Drum, like the standard drumset.
    // Its notes are General MIDI drum sounds
    private static boolean isGeneralDrums(Element part) {
        Element instrument = (Element) part.getElementsByTagName("Instrument").item(0);
        if (instrument == null) {
            return false;
        }
        String id = instrument.getAttribute("id");
        NodeList useDrumset = instrument.getElementsByTagName("useDrumset");
        return useDrumset.getLength() > 0 && useDrumset.item(0).getTextContent().trim().equals("1")
                && !id.equals("gw2drum") && !id.equals("gw2framedrum");
    }

    // Moves each drum sound to its pad on the drum kit (see DrumKit). Two sounds on one pad in a chord are one hit,
    // sounds with no pad are left out, and a chord left with no notes becomes a rest
    private static void mapDrums(Element staff) {
        for (Element chord : toList(staff.getElementsByTagName("Chord"))) {
            Set<Integer> pads = new HashSet<>();
            for (Element note : getChildElements(chord, "Note")) {
                Element pitch = getChildElements(note, "pitch").stream().findFirst().orElse(null);
                Integer pad = pitch == null ? null : DrumKit.padPitch(Integer.parseInt(pitch.getTextContent().trim()));
                if (pad == null || !pads.add(pad)) {
                    chord.removeChild(note);
                } else {
                    pitch.setTextContent(Integer.toString(pad));
                }
            }
            if (pads.isEmpty()) {
                if (isGrace(chord)) {
                    chord.getParentNode().removeChild(chord);
                    continue;
                }
                // A rest keeps the chord's length
                for (Element child : getChildElementsList(chord)) {
                    if (!child.getTagName().equals("durationType") && !child.getTagName().equals("dots")) {
                        chord.removeChild(child);
                    }
                }
                chord.getOwnerDocument().renameNode(chord, null, "Rest");
            }
        }
    }

    private static List<Element> toList(NodeList nodes) {
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            elements.add((Element) nodes.item(i));
        }
        return elements;
    }

    // Find which instrument the staff belongs to. MuseScore 4.6 and later leave out the staff ids in parts,
    // so then the staves are counted in order: staff n is the n-th staff listed in the parts
    private Element findPart(List<Element> parts, Element staff) {
        String staffId = staff.getAttribute("id");
        List<Element> partOfStaff = new ArrayList<>();
        for (Element candidatePart : parts) {
            if (candidatePart.getElementsByTagName("Instrument").getLength() == 0) {
                continue;
            }
            for (Element partStaff : getChildElements(candidatePart, "Staff")) {
                if (staffId.equals(partStaff.getAttribute("id"))) {
                    return candidatePart;
                }
                partOfStaff.add(candidatePart);
            }
        }
        try {
            int index = Integer.parseInt(staffId) - 1;
            if (index >= 0 && index < partOfStaff.size()) {
                return partOfStaff.get(index);
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("Staff " + staffId + " does not belong to a part with an instrument.");
    }

    // Moves every voice of the source staff into the matching measure of the target staff
    private void mergeStaffInto(Element target, Element source) {
        List<Element> sourceMeasures = getMeasures(source);
        int targetCount = getMeasures(target).size();
        if (sourceMeasures.size() > targetCount) {
            int extraNotes = 0;
            for (Element measure : sourceMeasures.subList(targetCount, sourceMeasures.size())) {
                extraNotes += measure.getElementsByTagName("Note").getLength();
            }
            if (extraNotes > 0) {
                String problem = "Staff " + source.getAttribute("id") + " has " + (sourceMeasures.size() - targetCount)
                        + " more measure(s) than staff " + target.getAttribute("id") + " (" + extraNotes + " note(s))";
                if (approximateTiming) {
                    padMeasures(target, sourceMeasures.size());
                } else {
                    droppedNotes.add(problem + ".");
                    hasExtraMeasures = true;
                }
            }
        }
        List<Element> targetMeasures = getMeasures(target);
        for (int i = 0; i < Math.min(targetMeasures.size(), sourceMeasures.size()); i++) {
            for (Element voice : getChildElements(sourceMeasures.get(i), "voice")) {
                targetMeasures.get(i).appendChild(voice);
            }
        }
    }

    // Adds empty measures to the end of a staff until it has the given number of measures
    private void padMeasures(Element staff, int measureCount) {
        Document doc = staff.getOwnerDocument();
        for (int i = getMeasures(staff).size(); i < measureCount; i++) {
            Element measure = doc.createElement("Measure");
            Element voice = doc.createElement("voice");
            voice.appendChild(createDurationElement(staff, "Rest", new String[]{"measure", "0"}));
            measure.appendChild(voice);
            staff.appendChild(measure);
        }
    }

    // Turns measures into the line's MusicElements. Rests are added onto the note before them
    // voices are the staff's first voice in each measure
    private ArrayList<MusicElement> processLine(List<Element> voices, int startMeasure, int numMeasures, List<Double> measureLengths) {
        ArrayList<MusicElement> line = new ArrayList<>();

        // Every element of the line's measures, and the length of the measure each one is in
        List<Element> voiceChildren = new ArrayList<>();
        Map<Element, Double> elementMeasureLengths = new IdentityHashMap<>();
        for (int i = 0; i < numMeasures; i++) {
            for (Element child : getChildElementsList(voices.get(i + startMeasure))) {
                // Grace notes take no time, so they are left out
                if (child.getTagName().equals("Beam") || (child.getTagName().equals("Chord") && isGrace(child))) {
                    continue;
                }
                voiceChildren.add(child);
                elementMeasureLengths.put(child, measureLengths.get(i + startMeasure));
            }
        }

        boolean insideTuplet = false;
        Element tupletElement = null;
        // For each element within the voice
        for (int j = 0; j < voiceChildren.size(); j++) {
            Element element = voiceChildren.get(j);
            boolean isFirst = false;
            boolean isLast = false;
            switch (element.getTagName()) {
                case "TimeSig":
                    int beats = Integer.parseInt(element.getElementsByTagName("sigN").item(0).getTextContent());
                    int noteLength = Integer.parseInt(element.getElementsByTagName("sigD").item(0).getTextContent());
                    line.add(new TimeSigMusicElement(null, 0, 0, beats, noteLength));
                    break;
                case "Chord":
                case "Rest":
                    // Get the chord's notes
                    ArrayList<Integer> notes = new ArrayList<>();
                    for (Element note : getChildElements(element, "Note")) {
                        notes.add(getPitch(note));
                    }
                    // A trill or tremolo adds the other notes it plays, all written inside tr{ }
                    boolean ornament = element.hasAttribute(Ornaments.ORNAMENT) && !notes.isEmpty();
                    if (ornament) {
                        for (List<Integer> chord : parseGraceChords(element.getAttribute(Ornaments.ORNAMENT))) {
                            for (int pitch : chord) {
                                if (!notes.contains(pitch)) {
                                    notes.add(pitch);
                                }
                            }
                        }
                    }

                    // The chord's duration, plus any rests right after it
                    double duration = getElementQuarters(element, elementMeasureLengths.get(element));
                    int k = 1;
                    while (k + j < voiceChildren.size() && voiceChildren.get(k + j).getTagName().equals("Rest")) {
                        Element restElement = voiceChildren.get(k + j);
                        duration += getElementQuarters(restElement, elementMeasureLengths.get(restElement));
                        k++;
                    }

                    // Get Arpeggio Type
                    Element arpeggioElement = (Element) element.getElementsByTagName("Arpeggio").item(0);
                    int arpeggioType = -1;

                    if (arpeggioElement != null) {
                        arpeggioType = Integer.parseInt(arpeggioElement.getElementsByTagName("subtype").item(0).getTextContent());
                    }

                    MusicElement musicElement;
                    if (insideTuplet) {
                        // Get tuplet info
                        double normalNotes = Double.parseDouble(tupletElement.getElementsByTagName("normalNotes").item(0).getTextContent());
                        double actualNotes = Double.parseDouble(tupletElement.getElementsByTagName("actualNotes").item(0).getTextContent());
                        if (j > 0 && voiceChildren.get(j - 1).getTagName().equals("Tuplet")) {
                            isFirst = true;
                        }
                        if (j + k < voiceChildren.size() && voiceChildren.get(j + k).getTagName().equals("endTuplet")) {
                            isLast = true;
                        }
                        musicElement = new TupletMusicElement(notes, duration, arpeggioType, normalNotes, actualNotes, isFirst, isLast);
                    } else {
                        musicElement = new MusicElement(notes, duration, arpeggioType);
                    }
                    musicElement.setGraceNotes(parseGraceChords(element.getAttribute(Ornaments.GRACE_BEFORE)),
                            parseGraceChords(element.getAttribute(Ornaments.GRACE_AFTER)));
                    musicElement.setOrnament(ornament);
                    line.add(musicElement);
                    // Skip over handled following rests
                    j += k - 1;
                    break;
                case "Tuplet":
                    tupletElement = element;
                    insideTuplet = true;
                    break;
                case "endTuplet":
                    tupletElement = null;
                    insideTuplet = false;
                    break;
            }
        }
        return line;
    }

    // Chords stored as pitches joined by "," and chords joined by ";", like "74;76,79"
    private List<List<Integer>> parseGraceChords(String text) {
        List<List<Integer>> chords = new ArrayList<>();
        for (String chordText : text.split(";")) {
            List<Integer> chord = new ArrayList<>();
            for (String pitch : chordText.split(",")) {
                if (!pitch.isBlank()) {
                    chord.add(Integer.parseInt(pitch.trim()));
                }
            }
            if (!chord.isEmpty()) {
                chords.add(chord);
            }
        }
        return chords;
    }

    // Length of a Chord or Rest in quarter notes; a full-measure rest lasts the whole measure
    private double getElementQuarters(Element element, double measureLength) {
        String durationType = element.getElementsByTagName("durationType").item(0).getTextContent();
        return durationType.equals("measure") ? measureLength : durationToQuarters(durationType, getDots(element));
    }

    private void initBoxes(Element staff) {
        int measureCounter = 0;
        for (Element measure : getMeasures(staff)) {
            measureCounter++;
            // Check if current measure has double bar line
            NodeList barLines = measure.getElementsByTagName("BarLine");
            boolean hasDoubleBarLine = false;
            for (int i = 0; i < barLines.getLength(); i++) {
                Element barLineElement = (Element) barLines.item(i);
                Element subtypeElement = (Element) barLineElement.getElementsByTagName("subtype").item(0);
                if (subtypeElement != null && subtypeElement.getTextContent().equals("double")) {
                    hasDoubleBarLine = true;
                    break;
                }
            }

            // Create box once double bar line is reached
            if (hasDoubleBarLine) {
                Box newBox = new Box(measureCounter);
                boxes.add(newBox);
                measureCounter = 0;
            }
        }

        // Add final Box
        Box newBox = new Box(measureCounter);
        boxes.add(newBox);
    }

    // Swing is written as straight notes, so "Swing" goes at the top of the box where it starts
    // (and "Straight" where it stops). MuseScore keeps it in <swing> tags of staff or system text
    private void labelSwingBoxes(List<Element> staves) {
        Map<Integer, Boolean> swingChanges = new TreeMap<>();
        for (Element staff : staves) {
            List<Element> measures = getMeasures(staff);
            for (int m = 0; m < measures.size(); m++) {
                NodeList swings = measures.get(m).getElementsByTagName("swing");
                for (int i = 0; i < swings.getLength(); i++) {
                    Element swing = (Element) swings.item(i);
                    String unit = swing.getAttribute("unit");
                    boolean on = (unit.equals("eighth") || unit.equals("16th")) && !swing.getAttribute("ratio").trim().equals("50");
                    swingChanges.put(m, on);
                }
            }
        }
        boolean swinging = false;
        int boxStart = 0;
        for (Box box : boxes) {
            for (int m = boxStart; m < boxStart + box.getNumMeasures(); m++) {
                Boolean on = swingChanges.get(m);
                if (on != null && on != swinging) {
                    swinging = on;
                    if (box.getLabel() == null) {
                        box.setLabel(on ? "Swing" : "Straight");
                    }
                }
            }
            boxStart += box.getNumMeasures();
        }
    }

    // Measures a new tempo must last to be marked, so short slowdowns and pauses aren't
    private static final int MIN_TEMPO_MEASURES = 2;
    // Beats per minute a tempo must change by to be marked
    private static final int MIN_TEMPO_CHANGE = 5;

    // Marks the starting tempo and time signature, and where they change, across from the line of the box they're on
    private void markTempoAndTimeSignatures(List<Element> staves) {
        int measureCount = boxes.stream().mapToInt(Box::getNumMeasures).sum();
        List<String> tempos = new ArrayList<>();
        List<String> timeSignatures = new ArrayList<>();
        for (int m = 0; m < measureCount; m++) {
            tempos.add(null);
            timeSignatures.add(null);
        }
        for (Element staff : staves) {
            List<Element> measures = getMeasures(staff);
            for (int m = 0; m < Math.min(measureCount, measures.size()); m++) {
                for (Element voice : getChildElements(measures.get(m), "voice")) {
                    for (Element tempo : getChildElements(voice, "Tempo")) {
                        if (tempos.get(m) == null) {
                            tempos.set(m, tempoText(tempo));
                        }
                    }
                    for (Element timeSig : getChildElements(voice, "TimeSig")) {
                        if (timeSignatures.get(m) == null) {
                            timeSignatures.set(m, timeSig.getElementsByTagName("sigN").item(0).getTextContent().trim()
                                    + "/" + timeSig.getElementsByTagName("sigD").item(0).getTextContent().trim());
                        }
                    }
                }
            }
        }
        String shownTempo = null;
        String shownTimeSignature = null;
        int boxStart = 0;
        for (Box box : boxes) {
            for (int m = boxStart; m < boxStart + box.getNumMeasures(); m++) {
                int line = (m - boxStart) / measuresPerRow;
                String tempo = tempos.get(m);
                // The first tempo is always marked; later ones only if they change enough and last.
                // A line has room for one tempo and one time signature, so a line with several shows the first
                if (tempo != null && tempoChanged(shownTempo, tempo) && (shownTempo == null || tempoLasts(tempos, m))) {
                    if (box.getMarking(line).getTempo() == null) {
                        box.getMarking(line).setTempo(tempo);
                    }
                    shownTempo = tempo;
                }
                String timeSignature = timeSignatures.get(m);
                if (timeSignature != null && !timeSignature.equals(shownTimeSignature)) {
                    if (box.getMarking(line).getTimeSignature() == null) {
                        box.getMarking(line).setTimeSignature(timeSignature);
                    }
                    shownTimeSignature = timeSignature;
                }
            }
            boxStart += box.getNumMeasures();
        }
    }

    // Whether a tempo is different enough from the one before (null for none) to be marked: a different note,
    // or a change of at least a few beats per minute
    private static boolean tempoChanged(String before, String after) {
        if (before == null) {
            return true;
        }
        String beforeNote = before.replaceAll("[0-9]", "");
        String afterNote = after.replaceAll("[0-9]", "");
        if (!beforeNote.equals(afterNote)) {
            return true;
        }
        int beforeBeats = Integer.parseInt(before.replaceAll("[^0-9]", ""));
        int afterBeats = Integer.parseInt(after.replaceAll("[^0-9]", ""));
        return Math.abs(afterBeats - beforeBeats) >= MIN_TEMPO_CHANGE;
    }

    // Whether the tempo starting at a measure lasts long enough to be marked, before a different one or the end
    private static boolean tempoLasts(List<String> tempos, int start) {
        if (start + MIN_TEMPO_MEASURES > tempos.size()) {
            return false;
        }
        for (int m = start + 1; m < start + MIN_TEMPO_MEASURES; m++) {
            if (tempos.get(m) != null && tempoChanged(tempos.get(start), tempos.get(m))) {
                return false;
            }
        }
        return true;
    }

    // A tempo marking like "♩120": quarter notes per minute, or dotted quarters or eighths if the marking uses them.
    // MuseScore keeps the tempo in quarter notes per second, and the note in the marking's text
    private static String tempoText(Element tempo) {
        double quartersPerMinute = Double.parseDouble(tempo.getElementsByTagName("tempo").item(0).getTextContent().trim()) * 60;
        Element textElement = (Element) tempo.getElementsByTagName("text").item(0);
        // Symbols like the note are <sym> tags, whose names are part of the text
        String text = textElement == null ? "" : textElement.getTextContent();
        if (text.contains("metNote8thUp")) {
            return "♪" + Math.round(quartersPerMinute * 2);
        }
        if (text.contains("metNoteQuarterUp") && text.contains("metAugmentationDot")) {
            return "♩." + Math.round(quartersPerMinute * 2 / 3);
        }
        return "♩" + Math.round(quartersPerMinute);
    }

    public ArrayList<Instrument> getProcessedInstruments() {
        return instrumentList;
    }

    public ArrayList<Box> getBoxes() {
        return boxes;
    }

    public String getTitle() {
        return title;
    }


    public List<String> getWarnings() {
        return warnings;
    }

    // Notes that were left out because they can't be placed exactly. Empty when approximateTiming is on
    public List<String> getDroppedNotes() {
        return droppedNotes;
    }

    public boolean hasUnalignedTupletNotes() {
        return voiceMerger.hasUnalignedTupletNotes();
    }

    public boolean hasExtraMeasures() {
        return hasExtraMeasures;
    }

}


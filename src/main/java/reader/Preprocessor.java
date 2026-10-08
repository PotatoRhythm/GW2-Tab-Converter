package reader;

import model.Box;
import model.Instrument;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        this.measuresPerRow = measuresPerRow;
        this.mergeStaves = mergeStaves;
        this.approximateTiming = approximateTiming;
        this.voiceMerger = new VoiceMerger(approximateTiming, droppedNotes);
        this.instrumentList = new ArrayList<>();
        this.boxes = new ArrayList<>();
        Document doc = readScore(musescoreFile);
        warnAboutUnsupportedDurations(doc);
        parseTitle(doc);
        if (title == null || title.isEmpty()) {
            // No title in the score, so use the file name 
            title = musescoreFile.getName().replaceFirst("\\.[^.]*$", "").replace('_', ' ').trim();
        }
        processInstruments(doc);
    }

    // Reads a MuseScore file
    private Document readScore(File musescoreFile) throws Exception {
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
        // Store lists for parts and staves
        NodeList parts = doc.getElementsByTagName("Part");
        NodeList unfilteredStaves = doc.getElementsByTagName("Staff");
        List<Element> allStaves = new ArrayList<>();
        for (int i = 0; i < unfilteredStaves.getLength(); i++) {
            Element staff = (Element) unfilteredStaves.item(i);
            Node parent = staff.getParentNode();
            if (parent == null || !parent.getNodeName().equals("Part")) {
                Ties.preprocessTies(staff);
                Ottavas.preprocessOttavas(staff, warnings);
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
            warnAboutGraceNotes(staff);
            voiceMerger.mergeVoices(staff);
        }

        int measureCount = outputStaves.stream().mapToInt(staff -> getMeasures(staff).size()).max().orElse(0);
        for (Element staff : outputStaves) {
            padMeasures(staff, measureCount);
        }
        initBoxes(outputStaves.get(0));

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
            int startMeasure = 0;
            ArrayList<ArrayList<MusicElement>> lines = new ArrayList<>();
            ArrayList<LineTiming> lineTimings = new ArrayList<>();
            for (Box box : boxes) {
                int remainingMeasures = box.getNumMeasures();
                for (int line = 0; line < box.getNumLines(measuresPerRow); line++) {
                    int lineMeasures = Math.min(remainingMeasures, measuresPerRow);
                    lines.add(processLine(stave, startMeasure, lineMeasures, measureLengths));
                    lineTimings.add(getLineTiming(measureLengths, timeSigLengths, startMeasure, lineMeasures));
                    startMeasure += lineMeasures;
                    remainingMeasures -= measuresPerRow;
                }
            }
            instrumentList.add(new Instrument(instrumentName, lines, lineTimings));
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

    // Grace notes take no time, so they can't be placed in the tab and are left out
    private void warnAboutGraceNotes(Element staff) {
        int graceNotes = 0;
        NodeList chords = staff.getElementsByTagName("Chord");
        for (int i = 0; i < chords.getLength(); i++) {
            if (isGrace((Element) chords.item(i))) {
                graceNotes += ((Element) chords.item(i)).getElementsByTagName("Note").getLength();
            }
        }
        if (graceNotes > 0) {
            warnings.add("Staff " + staff.getAttribute("id") + ": " + graceNotes + " grace note(s) were left out.");
        }
    }

    // Find which instrument the staff belongs to
    private Element findPart(NodeList parts, Element staff) {
        String staffId = staff.getAttribute("id");
        for (int p = 0; p < parts.getLength(); p++) {
            Element candidatePart = (Element) parts.item(p);
            if (candidatePart.getElementsByTagName("Instrument").getLength() == 0) {
                continue;
            }
            NodeList partStaffs = candidatePart.getElementsByTagName("Staff");
            for (int s = 0; s < partStaffs.getLength(); s++) {
                Element partStaff = (Element) partStaffs.item(s);
                if (staffId.equals(partStaff.getAttribute("id"))) {
                    return candidatePart;
                }
            }
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
    private ArrayList<MusicElement> processLine(Element staff, int startMeasure, int numMeasures, List<Double> measureLengths) {
        ArrayList<MusicElement> line = new ArrayList<>();
        List<Element> voices = getFirstVoices(staff);

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
                        line.add(new TupletMusicElement(notes, duration, arpeggioType, normalNotes, actualNotes, isFirst, isLast));
                    } else {
                        line.add(new MusicElement(notes, duration, arpeggioType));
                    }
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


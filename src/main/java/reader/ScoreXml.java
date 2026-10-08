package reader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Small helpers for reading the parts of a MuseScore file the converter needs
final class ScoreXml {
    private ScoreXml() {
    }

    static final double EPSILON = 1e-6;

    static final Set<String> GRACE_TAGS = Set.of("acciaccatura", "appoggiatura", "grace4", "grace16", "grace32",
            "grace8after", "grace16after", "grace32after");

    // Note values MuseScore writes, in quarter notes
    private static final Map<String, Double> NOTE_VALUES = Map.of(
            "breve", 8.0, "whole", 4.0, "half", 2.0, "quarter", 1.0, "eighth", 0.5,
            "16th", 0.25, "32nd", 0.125, "64th", 0.0625, "128th", 0.03125, "256th", 0.015625);

    static boolean isSupportedDuration(String duration) {
        return NOTE_VALUES.containsKey(duration);
    }

    // Quarter notes in a note value like "eighth", with 0-3 dots. 0 for a value the converter doesn't know
    static double durationToQuarters(String duration, int dotValue) {
        Double value = NOTE_VALUES.get(duration);
        if (value == null) {
            return 0;
        }
        double quarters = value;
        double dotValueQuarters = value;
        for (int i = 0; i < dotValue; i++) {
            dotValueQuarters /= 2;
            quarters += dotValueQuarters;
        }
        return quarters;
    }

    static List<Element> getChildElements(Element parent, String tagName) {
        List<Element> children = new ArrayList<>();
        NodeList childNodes = parent.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node child = childNodes.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(tagName)) {
                children.add((Element) child);
            }
        }
        return children;
    }

    // All child elements, in order
    static List<Element> getChildElementsList(Element parent) {
        List<Element> children = new ArrayList<>();
        NodeList childNodes = parent.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            if (childNodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) childNodes.item(i));
            }
        }
        return children;
    }

    static List<Element> getMeasures(Element staff) {
        return getChildElements(staff, "Measure");
    }

    // Returns the first <voice> of every measure, so index i is always measure i
    static List<Element> getFirstVoices(Element staff) {
        List<Element> voiceList = new ArrayList<>();
        for (Element measure : getMeasures(staff)) {
            List<Element> voices = getChildElements(measure, "voice");
            if (voices.isEmpty()) {
                throw new IllegalArgumentException("A measure in staff " + staff.getAttribute("id") + " has no voice.");
            }
            voiceList.add(voices.get(0));
        }
        return voiceList;
    }

    static int getPitch(Element note) {
        return Integer.parseInt(note.getElementsByTagName("pitch").item(0).getTextContent());
    }

    static int getDots(Element element) {
        Element dotsElement = (Element) element.getElementsByTagName("dots").item(0);
        return dotsElement == null ? 0 : Integer.parseInt(dotsElement.getTextContent());
    }

    // {durationType, dots} of a Chord or Rest
    static String[] getDuration(Element element) {
        String durationType = element.getElementsByTagName("durationType").item(0).getTextContent();
        return new String[]{durationType, Integer.toString(getDots(element))};
    }

    static boolean isGrace(Element chord) {
        for (Element child : getChildElementsList(chord)) {
            if (GRACE_TAGS.contains(child.getTagName())) {
                return true;
            }
        }
        return false;
    }

    static double getLocationQuarters(Element location) {
        Element fractions = (Element) location.getElementsByTagName("fractions").item(0);
        return fractions == null ? 0 : fractionToQuarters(fractions.getTextContent());
    }

    static double fractionToQuarters(String fraction) {
        String[] parts = fraction.trim().split("/");
        return 4.0 * Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
    }

    // Creates an empty Chord or Rest with the given {durationType, dots}
    static Element createDurationElement(Element staff, String tagName, String[] duration) {
        Document doc = staff.getOwnerDocument();
        Element element = doc.createElement(tagName);
        Element durationType = doc.createElement("durationType");
        durationType.setTextContent(duration[0]);
        element.appendChild(durationType);
        if (!duration[1].equals("0")) {
            Element dots = doc.createElement("dots");
            dots.setTextContent(duration[1]);
            element.appendChild(dots);
        }
        return element;
    }

    // Length of each measure in quarter notes; pickups and other irregular measures store their real length
    static List<Double> getMeasureLengths(Element staff) {
        List<Double> lengths = getTimeSigLengths(staff);
        List<Element> measures = getMeasures(staff);
        for (int m = 0; m < measures.size(); m++) {
            if (measures.get(m).hasAttribute("len")) {
                lengths.set(m, fractionToQuarters(measures.get(m).getAttribute("len")));
            }
        }
        return lengths;
    }

    // Length of a full measure in quarter notes at each measure, following time signature changes
    static List<Double> getTimeSigLengths(Element staff) {
        List<Double> lengths = new ArrayList<>();
        double timeSigLength = 4;
        for (Element measure : getMeasures(staff)) {
            for (Element voice : getChildElements(measure, "voice")) {
                for (Element timeSig : getChildElements(voice, "TimeSig")) {
                    int beats = Integer.parseInt(timeSig.getElementsByTagName("sigN").item(0).getTextContent());
                    int noteLength = Integer.parseInt(timeSig.getElementsByTagName("sigD").item(0).getTextContent());
                    timeSigLength = beats * (4.0 / noteLength);
                }
            }
            lengths.add(timeSigLength);
        }
        return lengths;
    }

    // Lists the Chords and Rests of a voice with their start time and length in quarter notes
    static List<VoiceEvent> getVoiceEvents(Element voice, double measureLength) {
        List<VoiceEvent> events = new ArrayList<>();
        for (VoiceEvent event : getTimedElements(voice, measureLength)) {
            String tagName = event.element.getTagName();
            if (tagName.equals("Chord") || tagName.equals("Rest")) {
                events.add(event);
            }
        }
        return events;
    }

    // Lists every element of a voice with the time it appears at. Only Chords and Rests have a length
    static List<VoiceEvent> getTimedElements(Element voice, double measureLength) {
        List<VoiceEvent> events = new ArrayList<>();
        double time = 0;
        double tupletRatio = 1;
        LinkedList<Double> tupletRatios = new LinkedList<>();
        int tupletIndex = -1;
        for (Element child : getChildElementsList(voice)) {
            int currentTuplet = tupletRatios.isEmpty() ? -1 : tupletIndex;
            double length = 0;
            switch (child.getTagName()) {
                case "location":
                    time += getLocationQuarters(child);
                    break;
                case "Tuplet":
                    double normalNotes = Double.parseDouble(child.getElementsByTagName("normalNotes").item(0).getTextContent());
                    double actualNotes = Double.parseDouble(child.getElementsByTagName("actualNotes").item(0).getTextContent());
                    if (tupletRatios.isEmpty()) {
                        tupletIndex++;
                    }
                    tupletRatios.push(tupletRatio);
                    tupletRatio *= normalNotes / actualNotes;
                    break;
                case "endTuplet":
                    tupletRatio = tupletRatios.isEmpty() ? 1 : tupletRatios.pop();
                    break;
                case "Chord":
                case "Rest":
                    // Grace notes take no time
                    if (!isGrace(child)) {
                        String durationType = child.getElementsByTagName("durationType").item(0).getTextContent();
                        length = durationType.equals("measure")
                                ? measureLength
                                : durationToQuarters(durationType, getDots(child)) * tupletRatio;
                    }
                    break;
            }
            events.add(new VoiceEvent(child, time, length, currentTuplet));
            time += length;
        }
        return events;
    }
}

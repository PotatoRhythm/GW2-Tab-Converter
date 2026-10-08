package reader;

import org.w3c.dom.Element;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import static reader.ScoreXml.*;

final class Ottavas {
    private Ottavas() {
    }

    // Shifts the notes under 8va/8vb/15ma/15mb/22ma/22mb lines.
    static void preprocessOttavas(Element staff, List<String> warnings) {
        List<Element> measures = getMeasures(staff);
        List<Double> measureLengths = getMeasureLengths(staff);
        List<double[]> starts = new ArrayList<>(); // {time, semitones}
        List<Double> ends = new ArrayList<>();
        List<VoiceEvent> chords = new ArrayList<>(); 
        double measureStart = 0;
        for (int m = 0; m < measures.size(); m++) {
            for (Element voice : getChildElements(measures.get(m), "voice")) {
                for (VoiceEvent event : getTimedElements(voice, measureLengths.get(m))) {
                    Element element = event.element;
                    double time = measureStart + event.onset;
                    if (element.getTagName().equals("Chord")) {
                        chords.add(new VoiceEvent(element, time, event.length, -1));
                    } else if (element.getTagName().equals("Spanner") && element.getAttribute("type").equals("Ottava")) {
                        if (!getChildElements(element, "next").isEmpty()) {
                            Element subtype = (Element) element.getElementsByTagName("subtype").item(0);
                            starts.add(new double[]{time, subtype == null ? 12 : getOttavaShift(subtype.getTextContent().trim(), warnings)});
                        } else if (!getChildElements(element, "prev").isEmpty()) {
                            ends.add(time);
                        }
                    }
                }
            }
            measureStart += measureLengths.get(m);
        }

        starts.sort(Comparator.comparingDouble(start -> start[0]));
        ends.sort(Comparator.naturalOrder());
        for (double[] start : starts) {
            // Each line ends at the first unused end after its start
            double end = Double.MAX_VALUE;
            for (Iterator<Double> it = ends.iterator(); it.hasNext(); ) {
                double candidate = it.next();
                if (candidate > start[0] + EPSILON) {
                    end = candidate;
                    it.remove();
                    break;
                }
            }
            for (VoiceEvent chord : chords) {
                if (chord.onset >= start[0] - EPSILON && chord.onset < end - EPSILON) {
                    shiftPitches(chord.element, (int) start[1]);
                }
            }
        }
    }

    // Semitones an ottava line moves its notes by
    private static int getOttavaShift(String subtype, List<String> warnings) {
        switch (subtype) {
            case "8va":
            case "0":
                return 12;
            case "8vb":
            case "1":
                return -12;
            case "15ma":
            case "2":
                return 24;
            case "15mb":
            case "3":
                return -24;
            case "22ma":
            case "4":
                return 36;
            case "22mb":
            case "5":
                return -36;
            default:
                warnings.add("Unknown ottava type \"" + subtype + "\" was ignored.");
                return 0;
        }
    }

    private static void shiftPitches(Element chord, int semitones) {
        for (Element note : getChildElements(chord, "Note")) {
            Element pitchElement = (Element) note.getElementsByTagName("pitch").item(0);
            pitchElement.setTextContent(Integer.toString(getPitch(note) + semitones));
        }
    }
}

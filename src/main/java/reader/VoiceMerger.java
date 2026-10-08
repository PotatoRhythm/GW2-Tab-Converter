package reader;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static reader.ScoreXml.*;

// Merges all the voices of each measure into voice 1, so the rest of the converter only reads voice 1
class VoiceMerger {
    // Elements that take up time in a voice, everything else is kept as is
    private static final Set<String> RHYTHM_TAGS = Set.of("Chord", "Rest", "Tuplet", "endTuplet", "Beam", "location");
    // Durations a single Chord/Rest can have, longest first
    private static final String[] DURATION_TYPES = {"whole", "half", "quarter", "eighth", "16th", "32nd", "64th", "128th", "256th"};
    // Grids (in quarter notes) that tuplet notes are moved to when timing is approximated, coarsest first
    private static final double[] APPROXIMATION_GRIDS = {0.125, 0.0625};

    private final boolean approximateTiming;
    private final List<String> droppedNotes;
    private boolean hasUnalignedTupletNotes = false;

    // A tuplet from one voice, kept as-is when voices are merged
    private static class TupletBlock {
        final List<Element> elements = new ArrayList<>(); // from <Tuplet> to <endTuplet>
        double start = Double.MAX_VALUE;
        double end = 0;

        boolean contains(double onset) {
            return onset >= start - EPSILON && onset < end - EPSILON;
        }
    }

    // Notes that can't be merged exactly are added to droppedNotes, unless approximateTiming keeps them at the nearest timing
    VoiceMerger(boolean approximateTiming, List<String> droppedNotes) {
        this.approximateTiming = approximateTiming;
        this.droppedNotes = droppedNotes;
    }

    // Merges every voice of each measure into voice 1, so the rest of the converter only has to read voice 1
    void mergeVoices(Element staff) {
        List<Element> measures = getMeasures(staff);
        List<Double> measureLengths = getMeasureLengths(staff);
        for (int m = 0; m < measures.size(); m++) {
            List<Element> voices = getChildElements(measures.get(m), "voice");
            if (voices.size() < 2) {
                continue;
            }
            double measureLength = measureLengths.get(m);

            List<List<VoiceEvent>> voiceEvents = new ArrayList<>();
            List<Integer> noteVoices = new ArrayList<>();
            for (int v = 0; v < voices.size(); v++) {
                List<VoiceEvent> events = getVoiceEvents(voices.get(v), measureLength);
                voiceEvents.add(events);
                if (events.stream().anyMatch(VoiceEvent::isNote)) {
                    noteVoices.add(v);
                }
            }
            // Nothing to merge if only voice 1 (or no voice) has notes
            if (noteVoices.isEmpty() || (noteVoices.size() == 1 && noteVoices.get(0) == 0)) {
                continue;
            }

            List<Integer> tupletVoices = new ArrayList<>();
            for (int v : noteVoices) {
                if (!getChildElements(voices.get(v), "Tuplet").isEmpty()) {
                    tupletVoices.add(v);
                }
            }
            String location = "Staff " + staff.getAttribute("id") + ", measure " + (m + 1);
            int unalignedNotes = countUnalignedNotes(voiceEvents, noteVoices, tupletVoices);
            if (unalignedNotes > 0 && approximateTiming) {
                mergeVoicesApproximately(staff, voices, voiceEvents, noteVoices, measureLength);
                continue;
            }
            if (unalignedNotes > 0) {
                droppedNotes.add(location + ": " + unalignedNotes + " note(s) don't line up with a tuplet in another voice.");
                hasUnalignedTupletNotes = true;
            }
            double measureEnd = getMeasureEnd(voiceEvents, measureLength);
            if (tupletVoices.size() <= 1) {
                Integer tupletVoice = tupletVoices.isEmpty() ? null : tupletVoices.get(0);
                mergeVoicesByTimeline(staff, voices, voiceEvents, noteVoices, tupletVoice, measureEnd);
            } else {
                mergeVoicesIntoTuplets(staff, voices, voiceEvents, noteVoices, tupletVoices.get(0));
            }
        }
    }

    // Counts notes from other voices that can't be merged without changing their timing
    private int countUnalignedNotes(List<List<VoiceEvent>> voiceEvents, List<Integer> noteVoices, List<Integer> tupletVoices) {
        if (tupletVoices.isEmpty()) {
            return 0;
        }
        int baseVoice = tupletVoices.get(0);
        boolean onlyTupletsFixed = tupletVoices.size() == 1;
        Set<Long> baseOnsets = new HashSet<>();
        for (VoiceEvent event : voiceEvents.get(baseVoice)) {
            if (event.length > 0 && (!onlyTupletsFixed || event.tupletIndex >= 0)) {
                baseOnsets.add(onsetKey(event.onset));
            }
        }
        List<double[]> spans = new ArrayList<>(getTupletSpans(voiceEvents.get(baseVoice)).values());
        int count = 0;
        for (int v : noteVoices) {
            if (v == baseVoice) {
                continue;
            }
            for (VoiceEvent event : voiceEvents.get(v)) {
                boolean fixed = !onlyTupletsFixed
                        || spans.stream().anyMatch(span -> event.onset >= span[0] - EPSILON && event.onset < span[1] - EPSILON);
                if (event.isNote() && fixed && !baseOnsets.contains(onsetKey(event.onset))) {
                    count += event.element.getElementsByTagName("Note").getLength();
                }
            }
        }
        return count;
    }

    // Start and end of each top-level tuplet in a voice, by tuplet index
    private Map<Integer, double[]> getTupletSpans(List<VoiceEvent> events) {
        Map<Integer, double[]> spans = new TreeMap<>();
        for (VoiceEvent event : events) {
            if (event.tupletIndex >= 0 && event.length > 0) {
                double[] span = spans.computeIfAbsent(event.tupletIndex, k -> new double[]{Double.MAX_VALUE, 0});
                span[0] = Math.min(span[0], event.onset);
                span[1] = Math.max(span[1], event.onset + event.length);
            }
        }
        return spans;
    }

    // Merges like mergeVoicesByTimeline, but first moves tuplet notes to the nearest 32nd (or 64th if two notes would land together) 
    // so every note can be placed. The tuplets become regular, often dotted, notes
    private void mergeVoicesApproximately(Element staff, List<Element> voices, List<List<VoiceEvent>> voiceEvents,
                                          List<Integer> noteVoices, double measureLength) {
        double measureEnd = getMeasureEnd(voiceEvents, measureLength);
        int onsetCount = countNoteOnsets(voiceEvents, noteVoices);
        List<List<VoiceEvent>> snapped = null;
        for (double grid : APPROXIMATION_GRIDS) {
            snapped = new ArrayList<>();
            for (List<VoiceEvent> events : voiceEvents) {
                List<VoiceEvent> snappedVoice = new ArrayList<>();
                for (VoiceEvent event : events) {
                    double onset = event.onset;
                    if (event.tupletIndex >= 0) {
                        onset = Math.min(Math.round(onset / grid) * grid, measureEnd - grid);
                    }
                    snappedVoice.add(new VoiceEvent(event.element, onset, event.length, -1));
                }
                snapped.add(snappedVoice);
            }
            if (countNoteOnsets(snapped, noteVoices) == onsetCount) {
                break;
            }
        }
        mergeVoicesByTimeline(staff, voices, snapped, noteVoices, null, measureEnd);
    }

    // Number of different times that notes start at
    private int countNoteOnsets(List<List<VoiceEvent>> voiceEvents, List<Integer> noteVoices) {
        Set<Long> onsets = new HashSet<>();
        for (int v : noteVoices) {
            for (VoiceEvent event : voiceEvents.get(v)) {
                if (event.isNote()) {
                    onsets.add(onsetKey(event.onset));
                }
            }
        }
        return onsets.size();
    }

    // End of the measure, or of its longest voice if that runs over
    private double getMeasureEnd(List<List<VoiceEvent>> voiceEvents, double measureLength) {
        double measureEnd = measureLength;
        for (List<VoiceEvent> events : voiceEvents) {
            for (VoiceEvent event : events) {
                measureEnd = Math.max(measureEnd, event.onset + event.length);
            }
        }
        return measureEnd;
    }

    // Combines all voices into one sequence: notes that start together become one chord,
    // and each chord lasts until the next note starts in any voice.
    private void mergeVoicesByTimeline(Element staff, List<Element> voices, List<List<VoiceEvent>> voiceEvents,
                                       List<Integer> noteVoices, Integer tupletVoice, double measureEnd) {
        // Tuplets of the tuplet voice, and its tuplet notes/rests by start time
        List<TupletBlock> blocks = new ArrayList<>();
        Map<Long, VoiceEvent> tupletEvents = new TreeMap<>();
        if (tupletVoice != null) {
            blocks = getTupletBlocks(voices.get(tupletVoice));
            for (Map.Entry<Integer, double[]> span : getTupletSpans(voiceEvents.get(tupletVoice)).entrySet()) {
                blocks.get(span.getKey()).start = span.getValue()[0];
                blocks.get(span.getKey()).end = span.getValue()[1];
            }
            for (VoiceEvent event : voiceEvents.get(tupletVoice)) {
                if (event.tupletIndex >= 0 && event.length > 0) {
                    tupletEvents.putIfAbsent(onsetKey(event.onset), event);
                }
            }
            blocks.removeIf(block -> block.end <= block.start);
        }

        // Every start time in the measure
        TreeMap<Long, List<Element>> chordsByOnset = new TreeMap<>();
        Map<Long, TupletBlock> blocksByOnset = new TreeMap<>();
        for (TupletBlock block : blocks) {
            blocksByOnset.put(onsetKey(block.start), block);
        }
        for (int v : noteVoices) {
            for (VoiceEvent event : voiceEvents.get(v)) {
                if (!event.isNote() || (tupletVoice != null && v == tupletVoice && event.tupletIndex >= 0)) {
                    continue;
                }
                // Notes during a tuplet that don't line up with it are left out (counted by countUnalignedNotes)
                if (blocks.stream().anyMatch(block -> block.contains(event.onset))) {
                    addToTuplet(staff, tupletEvents, event);
                    continue;
                }
                chordsByOnset.computeIfAbsent(onsetKey(event.onset), k -> new ArrayList<>()).add(event.element);
            }
        }

        TreeMap<Long, Boolean> onsets = new TreeMap<>();
        chordsByOnset.keySet().forEach(key -> onsets.put(key, true));
        blocksByOnset.keySet().forEach(key -> onsets.put(key, true));
        List<Long> onsetKeys = new ArrayList<>(onsets.keySet());

        List<Element> merged = new ArrayList<>();
        double firstOnset = keyToOnset(onsetKeys.get(0));
        if (firstOnset > EPSILON) {
            merged.addAll(createRests(staff, firstOnset));
        }
        for (int i = 0; i < onsetKeys.size(); i++) {
            double onset = keyToOnset(onsetKeys.get(i));
            double nextOnset = i + 1 < onsetKeys.size() ? keyToOnset(onsetKeys.get(i + 1)) : measureEnd;
            TupletBlock block = blocksByOnset.get(onsetKeys.get(i));
            if (block != null) {
                merged.addAll(block.elements);
                merged.addAll(createRests(staff, nextOnset - block.end));
                continue;
            }
            List<String[]> durations = splitDuration(nextOnset - onset);
            if (durations.isEmpty()) {
                continue;
            }
            merged.add(createMergedChord(staff, chordsByOnset.get(onsetKeys.get(i)), durations.get(0)));
            for (String[] duration : durations.subList(1, durations.size())) {
                merged.add(createDurationElement(staff, "Rest", duration));
            }
        }
        replaceRhythm(voices.get(0), merged);
    }

    // Adds a note from another voice to the tuplet chord or rest that starts at the same time.
    // Returns false if nothing in the tuplet starts then
    private boolean addToTuplet(Element staff, Map<Long, VoiceEvent> tupletEvents, VoiceEvent event) {
        long key = onsetKey(event.onset);
        VoiceEvent target = tupletEvents.get(key);
        if (target == null) {
            return false;
        }
        Element targetChord = target.element;
        if (targetChord.getTagName().equals("Rest")) {
            // A rest in the tuplet becomes a chord of the same length
            targetChord = createDurationElement(staff, "Chord", getDuration(target.element));
            target.element.getParentNode().replaceChild(targetChord, target.element);
            tupletEvents.put(key, new VoiceEvent(targetChord, target.onset, target.length, target.tupletIndex));
        }
        addNotes(targetChord, List.of(event.element));
        return true;
    }

    // Used when several voices have tuplets. The first tuplet voice is kept and notes from
    // other voices are added to its chords where they start at the same time
    private void mergeVoicesIntoTuplets(Element staff, List<Element> voices, List<List<VoiceEvent>> voiceEvents,
                                        List<Integer> noteVoices, int tupletVoice) {
        Map<Long, VoiceEvent> baseEvents = new TreeMap<>();
        for (VoiceEvent event : voiceEvents.get(tupletVoice)) {
            if (event.length > 0) {
                baseEvents.putIfAbsent(onsetKey(event.onset), event);
            }
        }
        for (int v : noteVoices) {
            if (v == tupletVoice) {
                continue;
            }
            // Notes that don't line up are left out (counted by countUnalignedNotes)
            for (VoiceEvent event : voiceEvents.get(v)) {
                if (event.isNote()) {
                    addToTuplet(staff, baseEvents, event);
                }
            }
        }

        if (tupletVoice != 0) {
            List<Element> moved = new ArrayList<>();
            for (Element child : getChildElementsList(voices.get(tupletVoice))) {
                if (child.getTagName().equals("location")) {
                    double gap = getLocationQuarters(child);
                    if (gap > EPSILON) {
                        moved.addAll(createRests(staff, gap));
                    }
                } else if (RHYTHM_TAGS.contains(child.getTagName())) {
                    moved.add(child);
                }
            }
            replaceRhythm(voices.get(0), moved);
        }
    }

    // The elements of each top-level tuplet in a voice, from <Tuplet> to <endTuplet>
    private List<TupletBlock> getTupletBlocks(Element voice) {
        List<TupletBlock> blocks = new ArrayList<>();
        int depth = 0;
        for (Element child : getChildElementsList(voice)) {
            if (child.getTagName().equals("Tuplet")) {
                if (depth == 0) {
                    blocks.add(new TupletBlock());
                }
                depth++;
            }
            if (depth > 0) {
                blocks.get(blocks.size() - 1).elements.add(child);
            }
            if (child.getTagName().equals("endTuplet") && depth > 0) {
                depth--;
            }
        }
        return blocks;
    }

    // Onsets are rounded so notes from different voices that start together share a key
    private long onsetKey(double onset) {
        return Math.round(onset / EPSILON);
    }

    private double keyToOnset(long key) {
        return key * EPSILON;
    }

    // Splits a length into durations a single Chord/Rest can have, such as 1.25 -> quarter + 16th
    private List<String[]> splitDuration(double quarters) {
        List<String[]> durations = new ArrayList<>();
        double remaining = quarters;
        while (remaining > EPSILON) {
            String[] best = null;
            double bestLength = 0;
            for (String type : DURATION_TYPES) {
                for (int dots = 0; dots <= 2; dots++) {
                    double length = durationToQuarters(type, dots);
                    if (length <= remaining + EPSILON && length > bestLength) {
                        best = new String[]{type, Integer.toString(dots)};
                        bestLength = length;
                    }
                }
            }
            if (best == null) {
                break; // shorter than a 256th note
            }
            durations.add(best);
            remaining -= bestLength;
        }
        return durations;
    }

    private List<Element> createRests(Element staff, double quarters) {
        List<Element> rests = new ArrayList<>();
        for (String[] duration : splitDuration(quarters)) {
            rests.add(createDurationElement(staff, "Rest", duration));
        }
        return rests;
    }

    private Element createMergedChord(Element staff, List<Element> sourceChords, String[] duration) {
        Element chord = createDurationElement(staff, "Chord", duration);
        for (Element source : sourceChords) {
            Element arpeggio = getChildElements(source, "Arpeggio").stream().findFirst().orElse(null);
            if (arpeggio != null) {
                chord.appendChild(arpeggio.cloneNode(true));
                break;
            }
        }
        addNotes(chord, sourceChords);
        return chord;
    }

    // Copies the notes of the source chords into the target chord, skipping repeated pitches,
    // and keeps the notes ordered from lowest to highest like MuseScore does
    private void addNotes(Element target, List<Element> sourceChords) {
        List<Element> notes = new ArrayList<>();
        for (Element existing : getChildElements(target, "Note")) {
            notes.add(existing);
            target.removeChild(existing);
        }
        for (Element source : sourceChords) {
            for (Element note : getChildElements(source, "Note")) {
                int pitch = getPitch(note);
                if (notes.stream().noneMatch(n -> getPitch(n) == pitch)) {
                    notes.add((Element) note.cloneNode(true));
                }
            }
        }
        notes.sort(Comparator.comparingInt(ScoreXml::getPitch));
        for (Element note : notes) {
            target.appendChild(note);
        }
    }

    // Replaces the timed contents of a voice with new elements, keeping clefs, time signatures, etc.
    private void replaceRhythm(Element voice, List<Element> newElements) {
        Node insertBefore = null;
        boolean seenRhythm = false;
        for (Element child : getChildElementsList(voice)) {
            if (RHYTHM_TAGS.contains(child.getTagName())) {
                seenRhythm = true;
                voice.removeChild(child);
            } else if (seenRhythm && insertBefore == null) {
                insertBefore = child;
            }
        }
        for (Element element : newElements) {
            voice.insertBefore(element, insertBefore);
        }
    }

    // Whether any notes were left out because they don't line up with a tuplet
    boolean hasUnalignedTupletNotes() {
        return hasUnalignedTupletNotes;
    }
}

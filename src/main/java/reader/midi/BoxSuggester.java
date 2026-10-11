package reader.midi;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

// Suggests where the boxes of a MIDI file start, like someone splitting the music into sections would.
// Each bar line gets points for looking like the start of a section: a marker, a time signature, key or tempo
// change, the start of a passage that repeats, instruments coming in or dropping out, or the music starting
// again after a silence. Boxes are then chosen to land on the best bar lines while staying near 8 measures long
final class BoxSuggester {
    // Box lengths in measures that cost nothing
    private static final int SHORTEST = 8;
    private static final int LONGEST = 16;
    // Points a bar line needs before it's worth starting a box there
    private static final double BOX_COST = 2.5;
    // Measures that must match an earlier passage to count as a repeat
    private static final int MIN_REPEAT = 4;

    private final int resolution;
    private final List<MidiReader.Measure> measures;
    private final boolean pickup;
    private final List<List<MidiPlayback.Event>> trackEvents;
    private final TreeMap<Long, Integer> keySigs;
    private final TreeMap<Long, Integer> tempos;
    private final TreeSet<Long> markers;
    // Measures with no notes, which are alike but don't make a repeat
    private boolean[] silent;

    BoxSuggester(int resolution, List<MidiReader.Measure> measures, boolean pickup, List<List<MidiPlayback.Event>> trackEvents,
                 TreeMap<Long, Integer> keySigs, TreeMap<Long, Integer> tempos, TreeSet<Long> markers) {
        this.resolution = resolution;
        this.measures = measures;
        this.pickup = pickup;
        this.trackEvents = trackEvents;
        this.keySigs = keySigs;
        this.tempos = tempos;
        this.markers = markers;
    }

    // Measures (counted from 0) where a new box starts, after the first
    List<Integer> suggest() {
        if (measures.size() < 2) {
            return List.of();
        }
        return chooseStarts(getPoints(), pickup ? 1 : 0);
    }

    // How much each bar line looks like the start of a section, by the measure after it
    private double[] getPoints() {
        int count = measures.size();
        double[] points = new double[count];
        addChangePoints(points);
        addTexturePoints(points);
        double[][] similarity = getSimilarities();
        addRepeatPoints(points, similarity);
        addSectionChangePoints(points, similarity);
        // A small preference for boxes lined up with the music's 4- and 8-measure phrases
        int firstFull = pickup ? 1 : 0;
        for (int m = firstFull + 1; m < count; m++) {
            points[m] += (m - firstFull) % 8 == 0 ? 1 : (m - firstFull) % 4 == 0 ? 0.5 : 0;
        }
        return points;
    }

    // Markers, and changes of time signature, key or tempo
    private void addChangePoints(double[] points) {
        long near = resolution / 2;
        for (int m = 1; m < measures.size(); m++) {
            MidiReader.Measure measure = measures.get(m);
            MidiReader.Measure previous = measures.get(m - 1);
            long start = measure.startTick;
            if (!markers.subSet(start - near, start + near).isEmpty()) {
                points[m] += 5;
            }
            boolean timeSigChange = measure.timeSig[0] != previous.timeSig[0] || measure.timeSig[1] != previous.timeSig[1];
            if (timeSigChange && !(pickup && m == 1)) {
                points[m] += 4;
            }
            if (!keySigs.floorEntry(start + near).getValue().equals(keySigs.floorEntry(start - near).getValue())) {
                points[m] += 4;
            }
            // A new tempo that lasts at least the measure, not a slowing down spread over many small changes
            int before = tempos.floorEntry(start - near).getValue();
            int after = tempos.floorEntry(start + near).getValue();
            int end = tempos.floorEntry(start + measure.lengthTicks - 1).getValue();
            if (Math.abs(after - before) > before * 0.1 && end == after) {
                points[m] += 2;
            }
        }
    }

    // Instruments coming in or dropping out, and the music starting again after a silent measure
    // or after a phrase ending on a long note
    private void addTexturePoints(double[] points) {
        int count = measures.size();
        List<BitSet> playing = new ArrayList<>();
        List<long[]> firstAndLast = new ArrayList<>(); // first and last note start in each measure, from its start
        for (int m = 0; m < count; m++) {
            playing.add(new BitSet());
            firstAndLast.add(new long[]{Long.MAX_VALUE, -1});
        }
        silent = new boolean[count];
        for (int t = 0; t < trackEvents.size(); t++) {
            for (MidiPlayback.Event event : trackEvents.get(t)) {
                int m = measureOf(event.tick);
                playing.get(m).set(t);
                long offset = Math.max(0, event.tick - measures.get(m).startTick);
                firstAndLast.get(m)[0] = Math.min(firstAndLast.get(m)[0], offset);
                firstAndLast.get(m)[1] = Math.max(firstAndLast.get(m)[1], offset);
            }
        }
        for (int m = 0; m < count; m++) {
            silent[m] = playing.get(m).isEmpty();
        }
        for (int m = 1; m < count; m++) {
            boolean silentBefore = playing.get(m - 1).isEmpty();
            boolean silentNow = playing.get(m).isEmpty();
            if (silentBefore && !silentNow) {
                points[m] += 3;
            } else if (silentNow && !silentBefore) {
                points[m] += 1;
            }
            if (m >= 2 && m + 1 < count) {
                BitSet before = (BitSet) playing.get(m - 2).clone();
                before.or(playing.get(m - 1));
                BitSet after = (BitSet) playing.get(m).clone();
                after.or(playing.get(m + 1));
                if (!before.isEmpty() && !after.isEmpty() && !before.equals(after)) {
                    points[m] += 1.5;
                }
            }
            // The previous measure's notes all start in its first half and are held, and this one starts on its first beat
            long lastBefore = firstAndLast.get(m - 1)[1];
            if (!silentBefore && lastBefore >= 0 && lastBefore <= measures.get(m - 1).lengthTicks / 2
                    && firstAndLast.get(m)[0] < resolution / 8) {
                points[m] += 1;
            }
        }
    }

    // How alike every pair of measures is, from 0 to 1: half from the notes at the same places in the measure,
    // half from the pitches played at all (so the same harmony with a different rhythm still counts)
    private double[][] getSimilarities() {
        int count = measures.size();
        List<Set<String>> placedNotes = new ArrayList<>();
        List<Set<Integer>> pitches = new ArrayList<>();
        for (int m = 0; m < count; m++) {
            placedNotes.add(new HashSet<>());
            pitches.add(new HashSet<>());
        }
        long step = Math.max(1, resolution / 8);
        for (int t = 0; t < trackEvents.size(); t++) {
            for (MidiPlayback.Event event : trackEvents.get(t)) {
                int m = measureOf(event.tick);
                long position = Math.round((event.tick - measures.get(m).startTick) / (double) step);
                for (int pitch : event.pitches) {
                    placedNotes.get(m).add(t + ":" + position + ":" + pitch);
                    pitches.get(m).add(pitch);
                }
            }
        }
        double[][] similarity = new double[count][count];
        for (int i = 0; i < count; i++) {
            for (int j = i; j < count; j++) {
                double value = 0.5 * overlap(placedNotes.get(i), placedNotes.get(j)) + 0.5 * overlap(pitches.get(i), pitches.get(j));
                similarity[i][j] = value;
                similarity[j][i] = value;
            }
        }
        return similarity;
    }

    // Share of two sets that they have in common; two empty sets are the same
    private static <T> double overlap(Set<T> a, Set<T> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        int shared = 0;
        for (T item : a) {
            if (b.contains(item)) {
                shared++;
            }
        }
        return shared / (double) (a.size() + b.size() - shared);
    }

    // A section change: the measures on each side of a bar line are alike, but different from the other side
    private void addSectionChangePoints(double[] points, double[][] similarity) {
        int count = measures.size();
        int window = 4;
        for (int m = 2; m <= count - 2; m++) {
            int before = Math.max(0, m - window);
            int after = Math.min(count, m + window);
            double within = (averageSimilarity(similarity, before, m, before, m) + averageSimilarity(similarity, m, after, m, after)) / 2;
            double across = averageSimilarity(similarity, before, m, m, after);
            points[m] += 6 * Math.max(0, within - across);
        }
    }

    private static double averageSimilarity(double[][] similarity, int fromA, int toA, int fromB, int toB) {
        double total = 0;
        int pairs = 0;
        for (int i = fromA; i < toA; i++) {
            for (int j = fromB; j < toB; j++) {
                if (i != j) {
                    total += similarity[i][j];
                    pairs++;
                }
            }
        }
        return pairs == 0 ? 0 : total / pairs;
    }

    // The start of a passage of several measures much like an earlier one, like a chorus coming back,
    // and the start of the earlier one. Their ends get a few points too
    private void addRepeatPoints(double[] points, double[][] similarity) {
        int count = measures.size();
        double alike = 0.7;
        double[] repeatPoints = new double[count];
        for (int lag = MIN_REPEAT; lag < count; lag++) {
            for (int first = 0; first + lag < count; first++) {
                int second = first + lag;
                // Only the start of each matching passage counts, not the measures inside it
                if (first > 0 && similarity[first - 1][second - 1] >= alike) {
                    continue;
                }
                int length = 0;
                while (second + length < count && first + length < second && similarity[first + length][second + length] >= alike
                        && !silent[first + length]) {
                    length++;
                }
                if (length < MIN_REPEAT) {
                    continue;
                }
                repeatPoints[second] += 3;
                repeatPoints[first] += 2;
                if (second + length < count) {
                    repeatPoints[second + length] += 1.5;
                }
                if (first + length < count) {
                    repeatPoints[first + length] += 1.5;
                }
            }
        }
        for (int m = 1; m < count; m++) {
            points[m] += Math.min(5, repeatPoints[m]);
        }
    }

    // The measure a tick is in, allowing for notes played just before their bar line
    private int measureOf(long tick) {
        long nudged = tick + resolution / 16;
        int low = 0;
        int high = measures.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (measures.get(middle).startTick <= nudged) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    // Picks the box starts with the most points, minus a cost for boxes far from 8 measures long.
    // A pickup stays in the first box
    private List<Integer> chooseStarts(double[] points, int firstFull) {
        int count = measures.size();
        double[] best = new double[count + 1];
        int[] previous = new int[count + 1];
        java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
        best[0] = 0;
        for (int end = 1; end <= count; end++) {
            for (int start = 0; start < end; start++) {
                if (best[start] == Double.NEGATIVE_INFINITY || (start > 0 && start <= firstFull)) {
                    continue;
                }
                int length = end - start - (start == 0 ? firstFull : 0);
                double value = best[start] + (start > 0 ? points[start] - BOX_COST : 0) - lengthCost(length, end == count);
                if (value > best[end]) {
                    best[end] = value;
                    previous[end] = start;
                }
            }
        }
        List<Integer> starts = new ArrayList<>();
        for (int start = previous[count]; start > 0; start = previous[start]) {
            starts.add(0, start);
        }
        return starts;
    }

    // Cost of a box this many measures long: nothing from 8 to 16, more the shorter or longer it is.
    // The last box can be short, since the music just ends
    private static double lengthCost(int length, boolean last) {
        double cost = 0;
        if (length < SHORTEST && !last) {
            cost += 0.5 * (SHORTEST - length) + (length < 2 ? 5 : 0);
        }
        if (length > LONGEST) {
            cost += 0.5 * (length - LONGEST);
        }
        return cost;
    }
}

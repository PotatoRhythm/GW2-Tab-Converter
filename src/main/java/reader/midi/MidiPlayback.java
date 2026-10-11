package reader.midi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

// Undoes what MIDI playback does to written notes, so a MIDI file reads more like its score:
// rolled chords become one chord, trills and tremolos become one note with an ornament,
// and grace notes become grace notes again instead of delaying the note after them
final class MidiPlayback {
    // Notes of one track that start together, or a note with its ornament or grace notes
    static class Event {
        long tick;
        long end; // when the last of its notes stops
        final TreeSet<Integer> pitches = new TreeSet<>();
        // The other notes of a trill or tremolo (empty for one repeated note), or null if it has none
        TreeSet<Integer> ornament;
        final List<List<Integer>> graceNotes = new ArrayList<>();
        boolean arpeggio;
        // When each of its notes stops, for telling held notes from short ones
        final List<Long> noteEnds = new ArrayList<>();
        final TreeMap<Integer, Long> endByPitch = new TreeMap<>();

        Event(long tick) {
            this.tick = tick;
        }
    }

    // MuseScore plays the notes of a rolled chord about 65 milliseconds apart, whatever the tempo
    private static final long ROLL_MILLIS = 80;
    // Fast runs with fewer notes than this are written as grace notes instead of tr{ }
    private static final int MIN_TRILL_NOTES = 5;

    private final int resolution;
    // Start of the beat (quarter note) a tick is in
    private final LongUnaryOperator beatStart;
    // Milliseconds between two ticks
    private final LongBinaryOperator millis;

    MidiPlayback(int resolution, LongUnaryOperator beatStart, LongBinaryOperator millis) {
        this.resolution = resolution;
        this.beatStart = beatStart;
        this.millis = millis;
    }

    // The notes of a track as events, with rolled chords, trills, tremolos and grace notes put back together
    List<Event> read(List<MidiReader.Note> notes) {
        List<Event> events = groupNotes(notes);
        events = joinRolledChords(events);
        events = joinOrnaments(events);
        events = joinGraceNotes(events);
        return events;
    }

    // Notes starting within a few ticks of each other are one event
    private List<Event> groupNotes(List<MidiReader.Note> notes) {
        List<MidiReader.Note> sorted = new ArrayList<>(notes);
        sorted.sort(Comparator.comparingLong((MidiReader.Note note) -> note.start).thenComparingInt(note -> note.pitch));
        List<Event> events = new ArrayList<>();
        long together = Math.max(1, resolution / 96);
        for (MidiReader.Note note : sorted) {
            Event last = events.isEmpty() ? null : events.get(events.size() - 1);
            if (last == null || note.start - last.tick > together) {
                last = new Event(note.start);
                events.add(last);
            }
            last.pitches.add(note.pitch);
            last.noteEnds.add(note.end);
            last.endByPitch.merge(note.pitch, note.end, Math::max);
            last.end = Math.max(last.end, note.end);
        }
        return events;
    }

    // A rolled (arpeggiated) chord is played as notes starting one after another that keep sounding together:
    // evenly spaced, closer than 32nds or a roll's time apart (written broken chords are slower), and going one way.
    // They become one chord, marked as an arpeggio when it has at least 3 notes
    private List<Event> joinRolledChords(List<Event> events) {
        List<Event> joined = new ArrayList<>();
        long step = resolution / 8;
        long evenness = Math.max(3, resolution / 96);
        long held = resolution / 8;
        for (int i = 0; i < events.size(); i++) {
            Event chord = events.get(i);
            int j = i;
            while (j + 1 < events.size()) {
                Event next = events.get(j + 1);
                long gap = next.tick - events.get(j).tick;
                long firstGap = j == i ? gap : events.get(i + 1).tick - chord.tick;
                boolean close = gap < step || millis.applyAsLong(events.get(j).tick, next.tick) <= ROLL_MILLIS;
                if (!close || Math.abs(gap - firstGap) > evenness || next.tick - chord.tick > resolution / 2
                        || next.pitches.size() != 1) {
                    break;
                }
                // Rolled up or down, so each note is above (or below) all the ones before it
                int pitch = next.pitches.first();
                boolean up = j == i ? pitch > chord.pitches.last() : events.get(i + 1).pitches.first() > chord.pitches.last();
                boolean continues = true;
                for (int k = i; k <= j; k++) {
                    continues &= up ? pitch > events.get(k).pitches.last() : pitch < events.get(k).pitches.first();
                }
                if (!continues) {
                    break;
                }
                // Every note so far must still be sounding well after the next one starts
                boolean allHeld = true;
                for (int k = i; k <= j; k++) {
                    for (long end : events.get(k).noteEnds) {
                        allHeld &= end - next.tick >= held;
                    }
                }
                if (!allHeld) {
                    break;
                }
                j++;
            }
            if (j > i) {
                Event merged = new Event(chord.tick);
                for (int k = i; k <= j; k++) {
                    merged.pitches.addAll(events.get(k).pitches);
                    merged.noteEnds.addAll(events.get(k).noteEnds);
                    merged.end = Math.max(merged.end, events.get(k).end);
                }
                merged.arpeggio = merged.pitches.size() >= 3;
                joined.add(merged);
                i = j;
            } else {
                joined.add(chord);
            }
        }
        return joined;
    }

    // Trills and tremolos are played as very fast notes (32nds or faster) that each stop before the next starts:
    // two pitches taking turns or one pitch repeated. With at least 5 notes, they become the first note with the
    // other pitch as its ornament, lasting the whole run, like tr{4/#4}. Shorter runs (like a short trill) become
    // grace notes before their last note, like {4#4}4. A run can't start in the middle of other fast notes,
    // like grace notes played at the end of a note (MuseScore 4)
    private List<Event> joinOrnaments(List<Event> events) {
        List<Event> joined = new ArrayList<>();
        long fast = resolution / 8 + 3;
        int lastRunEnd = -1;
        for (int i = 0; i < events.size(); i++) {
            boolean afterFastNote = i > 0 && i - 1 != lastRunEnd && events.get(i).tick - events.get(i - 1).tick <= fast
                    && events.get(i - 1).end <= events.get(i).tick + 2;
            if (afterFastNote) {
                joined.add(events.get(i));
                continue;
            }
            int j = i;
            while (j + 1 < events.size() && events.get(j + 1).tick - events.get(j).tick <= fast
                    && events.get(j).end <= events.get(j + 1).tick + 2 && continuesPattern(events, i, j + 1)
                    && !startsWrittenNote(events, i, j + 1)) {
                j++;
            }
            Event first = events.get(i);
            TreeSet<Integer> other = getOrnamentNotes(events, i, j);
            if (other == null) {
                joined.add(first);
                continue;
            }
            Event last = events.get(j);
            Event merged = new Event(first.tick);
            merged.graceNotes.addAll(first.graceNotes);
            if (j - i + 1 >= MIN_TRILL_NOTES) {
                merged.pitches.addAll(first.pitches);
                merged.ornament = other;
            } else {
                for (int k = i; k < j; k++) {
                    merged.graceNotes.add(new ArrayList<>(events.get(k).pitches));
                }
                merged.pitches.addAll(last.pitches);
            }
            merged.end = last.end;
            merged.noteEnds.add(merged.end);
            joined.add(merged);
            lastRunEnd = j;
            i = j;
        }
        return joined;
    }

    // Whether event k is a new written note rather than part of the run: it's on the grid after a gap
    // noticeably different from the run's, like a tremolo followed by another tremolo on the same note
    private boolean startsWrittenNote(List<Event> events, int i, int k) {
        if (k < i + 2) {
            return false;
        }
        long runStep = events.get(i + 1).tick - events.get(i).tick;
        long step = events.get(k).tick - events.get(k - 1).tick;
        return Math.abs(step - runStep) > 3 && isOnGrid(events.get(k).tick);
    }

    // Whether event k keeps up a trill or tremolo starting at event i: after the first event (which can have extra
    // notes, like a held bass note), the events take turns between two sets of notes, and every second one is
    // in the first event
    private static boolean continuesPattern(List<Event> events, int i, int k) {
        if (k == i + 1) {
            return true;
        }
        if (k == i + 2) {
            return events.get(i).pitches.containsAll(events.get(k).pitches);
        }
        return events.get(k).pitches.equals(events.get(k - 2).pitches);
    }

    // For a run of fast events from i to j that keeps up the pattern: the notes taking turns with the first
    // event's notes (empty if it repeats), or null if the run is too short to be an ornament
    private static TreeSet<Integer> getOrnamentNotes(List<Event> events, int i, int j) {
        int length = j - i + 1;
        if (length < 3) {
            return null;
        }
        TreeSet<Integer> a = events.get(i + 1).pitches;
        TreeSet<Integer> b = events.get(i + 2).pitches;
        if (a.equals(b)) {
            // One note (or chord) repeated
            return new TreeSet<>();
        }
        TreeSet<Integer> other = new TreeSet<>(a);
        other.removeAll(events.get(i).pitches);
        return other.isEmpty() ? null : other;
    }

    // Grace notes are played as short notes on the beat that push the main note off the beat.
    // They become grace notes of the main note, which moves back to where they started. Only used when the
    // main note is off the grid and lasts well beyond them, so fast written notes aren't mistaken for them
    private List<Event> joinGraceNotes(List<Event> events) {
        long shortNote = resolution / 6 + 3;
        int maxGraceNotes = 4;
        List<Event> joined = new ArrayList<>(events);
        for (int m = joined.size() - 1; m > 0; m--) {
            Event main = joined.get(m);
            // MuseScore 4 makes each grace note a triplet 16th, so only the straight grid counts here
            if (isOnStraightGrid(main.tick)) {
                continue;
            }
            int first = -1;
            for (int k = m - 1; k >= 0 && m - k <= maxGraceNotes; k--) {
                Event grace = joined.get(k);
                long nextTick = joined.get(k + 1).tick;
                // The first grace note can start with a held note from another voice, which joins the main note
                boolean quick = nextTick - grace.tick <= shortNote && !shortPitches(grace, nextTick).isEmpty()
                        && grace.ornament == null && grace.graceNotes.isEmpty();
                boolean onGrid = isOnStraightGrid(grace.tick);
                if (!quick || (shortPitches(grace, nextTick).size() < grace.pitches.size() && !onGrid)) {
                    break;
                }
                if (onGrid) {
                    first = k;
                    break;
                }
            }
            if (first < 0) {
                continue;
            }
            long delay = main.tick - joined.get(first).tick;
            if (main.end - main.tick < 2 * delay) {
                continue;
            }
            for (int k = first; k < m; k++) {
                Event grace = joined.get(k);
                List<Integer> graceChord = shortPitches(grace, joined.get(k + 1).tick);
                main.graceNotes.add(graceChord);
                for (int pitch : grace.pitches) {
                    if (!graceChord.contains(pitch)) {
                        main.pitches.add(pitch);
                    }
                }
            }
            main.tick = joined.get(first).tick;
            joined.subList(first, m).clear();
            m = first;
        }
        return joined;
    }

    // Notes of an event that stop by the time the next event starts
    private static List<Integer> shortPitches(Event event, long nextTick) {
        List<Integer> pitches = new ArrayList<>();
        for (int pitch : event.pitches) {
            if (event.endByPitch.getOrDefault(pitch, event.end) <= nextTick + 2) {
                pitches.add(pitch);
            }
        }
        return pitches;
    }

    // Whether a tick is exactly on a 32nd note or triplet 16th of its beat
    private boolean isOnStraightGrid(long tick) {
        return onGrid(tick - beatStart.applyAsLong(tick), resolution / 8.0);
    }

    private boolean isOnGrid(long tick) {
        long offset = tick - beatStart.applyAsLong(tick);
        return onGrid(offset, resolution / 8.0) || onGrid(offset, resolution / 6.0);
    }

    private static boolean onGrid(long offset, double grid) {
        return Math.abs(offset - Math.round(offset / grid) * grid) <= 1;
    }
}

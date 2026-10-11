package reader.midi;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongUnaryOperator;
import java.util.stream.Collectors;

import reader.DrumKit;
import reader.Ornaments;
import reader.ScoreXml;

import static reader.ScoreXml.splitDuration;

// Reads a MIDI file and builds a MuseScore score from it, so it can be converted like a .mscx file.
// Each track (and each channel within a track) becomes a staff. Notes are snapped to a straight or triplet grid
// per beat, and each chord lasts until the next one starts, the same as tied notes and rests are read in scores
public class MidiReader {
    // Positions in the built score are counted in units of a 48th of a quarter note: a 32nd note is 6
    // and a triplet 16th is 8
    private static final int UNITS = 48;
    private static final int PERCUSSION_CHANNEL = 9;
    // The keys MIDI tracks are written with: every note, like GW2's piano. Columns are named after the
    // General MIDI instrument instead, which the user can rename
    private static final String KEYS = "Piano";
    // Attribute of a built part with its column's name, which Preprocessor reads
    public static final String COLUMN_NAME = "gw2ColumnName";
    // General MIDI instrument names by program number, without "Acoustic" to keep them short
    private static final String[] PROGRAM_NAMES = {
            "Grand Piano", "Bright Piano", "Electric Grand Piano", "Honky-tonk Piano", "Electric Piano 1",
            "Electric Piano 2", "Harpsichord", "Clavinet", "Celesta", "Glockenspiel", "Music Box", "Vibraphone", "Marimba",
            "Xylophone", "Tubular Bells", "Dulcimer", "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ",
            "Reed Organ", "Accordion", "Harmonica", "Tango Accordion", "Nylon Guitar", "Steel Guitar", "Jazz Guitar",
            "Clean Electric Guitar", "Muted Electric Guitar", "Overdriven Guitar", "Distortion Guitar", "Guitar Harmonics",
            "Bass", "Finger Bass", "Pick Bass", "Fretless Bass", "Slap Bass 1", "Slap Bass 2", "Synth Bass 1",
            "Synth Bass 2", "Violin", "Viola", "Cello", "Contrabass", "Tremolo Strings", "Pizzicato Strings",
            "Orchestral Harp", "Timpani", "String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2",
            "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit", "Trumpet", "Trombone", "Tuba", "Muted Trumpet",
            "French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2", "Soprano Sax", "Alto Sax", "Tenor Sax",
            "Baritone Sax", "Oboe", "English Horn", "Bassoon", "Clarinet", "Piccolo", "Flute", "Recorder", "Pan Flute",
            "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina", "Square Lead", "Sawtooth Lead", "Calliope Lead",
            "Chiff Lead", "Charang Lead", "Voice Lead", "Fifths Lead", "Bass and Lead", "New Age Pad", "Warm Pad",
            "Polysynth Pad", "Choir Pad", "Bowed Pad", "Metallic Pad", "Halo Pad", "Sweep Pad", "Rain", "Soundtrack",
            "Crystal", "Atmosphere", "Brightness", "Goblins", "Echoes", "Sci-fi", "Sitar", "Banjo", "Shamisen", "Koto",
            "Kalimba", "Bagpipe", "Fiddle", "Shanai", "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock", "Taiko Drum",
            "Melodic Tom", "Synth Drum", "Reverse Cymbal", "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet",
            "Telephone Ring", "Helicopter", "Applause", "Gunshot"};

    // A note from the file, in ticks
    static class Note {
        final long start;
        long end;
        final int pitch;

        Note(long start, int pitch) {
            this.start = start;
            this.end = start;
            this.pitch = pitch;
        }
    }

    // The notes of one channel of one track: a staff in the built score
    public static class MidiTrack {
        private final String name;
        private final int channel;
        private final int program;
        // Whether the track sets its channel's instrument itself. MuseScore leaves this out of a grand staff's
        // second staff, since the first one already set it
        private final boolean setsProgram;
        // Index of the file's track the notes are in
        private final int fileTrack;
        final List<Note> notes = new ArrayList<>();

        private MidiTrack(String name, int channel, int program, boolean setsProgram, int fileTrack) {
            this.name = name;
            this.channel = channel;
            this.program = program;
            this.setsProgram = setsProgram;
            this.fileTrack = fileTrack;
        }

        public String getName() {
            return name;
        }

        public int getNoteCount() {
            return notes.size();
        }

        public List<Integer> getPitches() {
            List<Integer> pitches = new ArrayList<>();
            for (Note note : notes) {
                pitches.add(note.pitch);
            }
            return pitches;
        }

        public int getLowest() {
            return notes.stream().mapToInt(note -> note.pitch).min().orElse(0);
        }

        public int getHighest() {
            return notes.stream().mapToInt(note -> note.pitch).max().orElse(0);
        }

        // Whether this track is another staff of the same part as the track before it, like a piano's other hand:
        // the same channel, and the same name or no instrument of its own. MuseScore plays each part on its own
        // channel, and names its staves alike or not at all. Some other programs put every track on one channel,
        // but then each track sets its own instrument. Drum tracks all share a channel, so stay apart
        public boolean isSamePartAs(MidiTrack before) {
            return channel == before.channel && channel != PERCUSSION_CHANNEL && (name.equals(before.name) || !setsProgram);
        }

        // The General MIDI instrument the track is played with
        public String getProgramName() {
            return channel == PERCUSSION_CHANNEL ? "Drum Kit" : PROGRAM_NAMES[Math.max(0, Math.min(127, program))];
        }

        // A General MIDI drum track, written for the drum kit
        public boolean isDrums() {
            return channel == PERCUSSION_CHANNEL;
        }
    }

    private final String title;
    private final int resolution;
    private final List<MidiTrack> tracks = new ArrayList<>();
    private final TreeMap<Long, int[]> timeSigs = new TreeMap<>(); // tick -> {beats, note length}
    private final TreeMap<Long, Integer> keySigs = new TreeMap<>(); // tick -> sharps (negative for flats)
    private final TreeMap<Long, Integer> tempos = new TreeMap<>(); // tick -> microseconds per quarter note
    private final TreeSet<Long> markers = new TreeSet<>(); // ticks of marker and cue point events, like "Chorus"
    private long lastTick = 0;
    private final List<String> warnings = new ArrayList<>();

    // The file as read, for playing it
    private final Sequence sequence;

    public MidiReader(File file) throws InvalidMidiDataException, IOException {
        title = file.getName().replaceFirst("\\.[^.]*$", "").replace('_', ' ').trim();
        sequence = MidiSystem.getSequence(file);
        if (sequence.getDivisionType() != Sequence.PPQ) {
            throw new InvalidMidiDataException("MIDI files timed in SMPTE frames aren't supported, only ones timed in beats.");
        }
        resolution = sequence.getResolution();

        // Programs are per channel, and can be set in a different track to the notes
        Map<Integer, Integer> programs = new HashMap<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof ShortMessage && ((ShortMessage) message).getCommand() == ShortMessage.PROGRAM_CHANGE) {
                    programs.putIfAbsent(((ShortMessage) message).getChannel(), ((ShortMessage) message).getData1());
                }
            }
        }

        int trackNumber = 0;
        for (Track track : sequence.getTracks()) {
            trackNumber++;
            readTrack(track, trackNumber, programs);
        }
        timeSigs.putIfAbsent(0L, new int[]{4, 4});
        keySigs.putIfAbsent(0L, 0);
        tempos.putIfAbsent(0L, 500000); // 120 beats per minute
        if (tracks.isEmpty()) {
            throw new InvalidMidiDataException("The MIDI file has no notes.");
        }
    }

    private void readTrack(Track track, int trackNumber, Map<Integer, Integer> programs) {
        String trackName = "";
        Map<Integer, MidiTrack> channels = new TreeMap<>();
        Set<Integer> programChannels = new HashSet<>();
        // Notes that are still sounding, by channel and pitch
        Map<Integer, Deque<Note>> sounding = new HashMap<>();
        // Drum hits by tick and pad, so two sounds on one pad at once are one hit
        Set<Long> drumHits = new HashSet<>();
        for (int i = 0; i < track.size(); i++) {
            MidiEvent event = track.get(i);
            MidiMessage message = event.getMessage();
            lastTick = Math.max(lastTick, event.getTick());
            if (message instanceof MetaMessage) {
                MetaMessage meta = (MetaMessage) message;
                byte[] data = meta.getData();
                if (meta.getType() == 0x03 && trackName.isEmpty()) {
                    trackName = new String(data, StandardCharsets.UTF_8).trim();
                } else if (meta.getType() == 0x58 && data.length >= 2) {
                    timeSigs.put(event.getTick(), new int[]{data[0], 1 << data[1]});
                } else if (meta.getType() == 0x59 && data.length >= 1) {
                    keySigs.put(event.getTick(), (int) data[0]);
                } else if (meta.getType() == 0x06 || meta.getType() == 0x07) {
                    markers.add(event.getTick());
                } else if (meta.getType() == 0x51 && data.length >= 3) {
                    tempos.put(event.getTick(), ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff));
                }
            } else if (message instanceof ShortMessage) {
                ShortMessage shortMessage = (ShortMessage) message;
                int channel = shortMessage.getChannel();
                if (shortMessage.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                    programChannels.add(channel);
                }
                int key = channel * 128 + shortMessage.getData1();
                boolean noteOn = shortMessage.getCommand() == ShortMessage.NOTE_ON && shortMessage.getData2() > 0;
                boolean noteOff = shortMessage.getCommand() == ShortMessage.NOTE_OFF
                        || (shortMessage.getCommand() == ShortMessage.NOTE_ON && shortMessage.getData2() == 0);
                if (noteOn) {
                    // Drum sounds become the drum kit's pads (see DrumKit). Sounds with no pad are left out, and two sounds on one pad
                    // at the same time are one hit
                    Integer pitch = channel == PERCUSSION_CHANNEL ? DrumKit.padPitch(shortMessage.getData1()) : (Integer) shortMessage.getData1();
                    Note note = new Note(event.getTick(), pitch == null ? -1 : pitch);
                    sounding.computeIfAbsent(key, k -> new ArrayDeque<>()).add(note);
                    if (pitch != null && (channel != PERCUSSION_CHANNEL || drumHits.add(event.getTick() * 128 + pitch))) {
                        channels.computeIfAbsent(channel, c -> new MidiTrack("", c, programs.getOrDefault(c, 0), false, 0))
                                .notes.add(note);
                    }
                } else if (noteOff && sounding.containsKey(key) && !sounding.get(key).isEmpty()) {
                    sounding.get(key).poll().end = event.getTick();
                }
            }
        }
        // Notes never turned off last until the end of the track
        long trackEnd = track.ticks();
        sounding.values().forEach(notes -> notes.forEach(note -> note.end = Math.max(note.end, trackEnd)));
        for (MidiTrack channelNotes : channels.values()) {
            String name = trackName.isEmpty() ? "Track " + trackNumber : trackName;
            if (channels.size() > 1) {
                name += " (channel " + (channelNotes.channel + 1) + ")";
            }
            MidiTrack named = new MidiTrack(name, channelNotes.channel, channelNotes.program,
                    programChannels.contains(channelNotes.channel), trackNumber - 1);
            named.notes.addAll(channelNotes.notes);
            tracks.add(named);
        }
    }

    public String getTitle() {
        return title;
    }

    public List<MidiTrack> getTracks() {
        return tracks;
    }

    public Sequence getSequence() {
        return sequence;
    }

    // The file with only the notes of the included tracks (in the order of getTracks()),
    // for playing what the tab will have. Tempos, instruments and everything else are kept
    public Sequence getPlaybackSequence(List<Boolean> included) throws InvalidMidiDataException {
        Set<Long> kept = new HashSet<>();
        for (int i = 0; i < tracks.size() && i < included.size(); i++) {
            if (included.get(i)) {
                kept.add(tracks.get(i).fileTrack * 16L + tracks.get(i).channel);
            }
        }
        Sequence playback = new Sequence(sequence.getDivisionType(), sequence.getResolution());
        Track[] fileTracks = sequence.getTracks();
        for (int t = 0; t < fileTracks.length; t++) {
            Track copy = playback.createTrack();
            for (int i = 0; i < fileTracks[t].size(); i++) {
                MidiEvent event = fileTracks[t].get(i);
                if (event.getMessage() instanceof ShortMessage) {
                    ShortMessage message = (ShortMessage) event.getMessage();
                    boolean note = message.getCommand() == ShortMessage.NOTE_ON || message.getCommand() == ShortMessage.NOTE_OFF;
                    if (note && !kept.contains(t * 16L + message.getChannel())) {
                        continue;
                    }
                }
                copy.add(event);
            }
        }
        return playback;
    }

    // Things the user should know about the last built score, like swing being written straight
    public List<String> getWarnings() {
        return warnings;
    }

    // ===== Building the score =====

    // A measure of the built score
    static class Measure {
        final long startTick;
        final long lengthTicks;
        final int[] timeSig;

        Measure(long startTick, long lengthTicks, int[] timeSig) {
            this.startTick = startTick;
            this.lengthTicks = lengthTicks;
            this.timeSig = timeSig;
        }
    }

    // The chosen tracks read into measures and events, ready to suggest boxes or build the score from
    private class Analysis {
        final List<MidiTrack> chosen = new ArrayList<>();
        final List<Measure> measures;
        final boolean pickup;
        final TreeMap<Long, Measure> measuresByStart = new TreeMap<>();
        final LongUnaryOperator beatStart;
        final List<List<MidiPlayback.Event>> trackEvents = new ArrayList<>();

        Analysis(List<Boolean> included) {
            for (int i = 0; i < tracks.size(); i++) {
                if (i < included.size() && included.get(i)) {
                    chosen.add(tracks.get(i));
                }
            }
            if (chosen.isEmpty()) {
                throw new IllegalArgumentException("No tracks were chosen to convert.");
            }
            measures = getMeasures(chosen);
            pickup = hasPickup(measures);
            for (Measure measure : measures) {
                measuresByStart.put(measure.startTick, measure);
            }
            beatStart = tick -> {
                Measure measure = measuresByStart.floorEntry(Math.max(0, tick)).getValue();
                return measure.startTick + (tick - measure.startTick) / resolution * resolution;
            };
            // Milliseconds from a tick to a later one, at the tempo where it starts
            java.util.function.LongBinaryOperator millis = (from, to) ->
                    (to - from) * tempos.floorEntry(Math.max(0, from)).getValue() / resolution / 1000;
            MidiPlayback playback = new MidiPlayback(resolution, beatStart, millis);
            for (MidiTrack track : chosen) {
                trackEvents.add(playback.read(track.notes));
            }
        }

        List<Integer> suggestBoxStarts() {
            return new BoxSuggester(resolution, measures, pickup, trackEvents, keySigs, tempos, markers).suggest();
        }
    }

    // The measures the chosen tracks fill, for the user to place boxes on before converting
    public static class MeasurePlan {
        private final boolean pickup;
        private final List<Long> measureStarts;

        MeasurePlan(boolean pickup, List<Long> measureStarts) {
            this.pickup = pickup;
            this.measureStarts = measureStarts;
        }

        public int getMeasureCount() {
            return measureStarts.size();
        }

        // Whether the first measure is a pickup, numbered 0 like in MuseScore
        public boolean hasPickup() {
            return pickup;
        }

        // The tick each measure starts at in the file, for playing from it
        public List<Long> getMeasureStarts() {
            return measureStarts;
        }
    }

    public synchronized MeasurePlan getMeasurePlan(List<Boolean> included) {
        Analysis analysis = new Analysis(included);
        return new MeasurePlan(analysis.pickup,
                analysis.measures.stream().map(measure -> measure.startTick).collect(Collectors.toList()));
    }

    // Which tracks are converted unless the user chooses: all of them
    public List<Boolean> getDefaultTracks() {
        return tracks.stream().map(track -> true).collect(Collectors.toList());
    }

    // Builds a MuseScore score from the included tracks (in the order of getTracks()), each part named after its
    // General MIDI instrument. boxStarts are the measures (counted from 0) that start a new box, or null for the
    // suggested ones. Synchronized since the box preview builds scores in the background
    public synchronized Document toScore(List<Boolean> included, java.util.Collection<Integer> boxStarts) throws ParserConfigurationException {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element museScore = doc.createElement("museScore");
        doc.appendChild(museScore);
        Element score = doc.createElement("Score");
        museScore.appendChild(score);
        Element metaTag = doc.createElement("metaTag");
        metaTag.setAttribute("name", "workTitle");
        metaTag.setTextContent(title);
        score.appendChild(metaTag);

        Analysis analysis = new Analysis(included);
        List<MidiTrack> chosen = analysis.chosen;
        TreeSet<Integer> starts = new TreeSet<>(boxStarts == null ? analysis.suggestBoxStarts() : boxStarts);

        // Tracks next to each other of the same part, like a piano's two hands, are one part
        Element part = null;
        Element instrument = null;
        for (int i = 0; i < chosen.size(); i++) {
            boolean samePart = i > 0 && chosen.get(i).isSamePartAs(chosen.get(i - 1));
            if (!samePart) {
                part = doc.createElement("Part");
                part.setAttribute(COLUMN_NAME, chosen.get(i).getProgramName());
                score.appendChild(part);
                instrument = doc.createElement("Instrument");
                instrument.setAttribute("id", "gw2" + (chosen.get(i).isDrums() ? DrumKit.KEYS : KEYS).toLowerCase(Locale.ROOT));
                part.appendChild(instrument);
            }
            // A part's staves come before its instrument, like in MuseScore files
            Element partStaff = doc.createElement("Staff");
            partStaff.setAttribute("id", Integer.toString(i + 1));
            part.insertBefore(partStaff, instrument);
        }

        List<Measure> measures = analysis.measures;
        List<List<MidiPlayback.Event>> trackEvents = analysis.trackEvents;
        warnings.clear();
        Swing swing = findSwing(trackEvents, analysis.beatStart);
        if (swing != null) {
            warnings.add("Swing was found (off-beat eighths played " + swing.ratio
                    + "% of the way through the beat). It's written as straight eighths, with Swing at the top of the box.");
        }
        for (int i = 0; i < chosen.size(); i++) {
            Element staff = doc.createElement("Staff");
            staff.setAttribute("id", Integer.toString(i + 1));
            score.appendChild(staff);
            writeStaff(staff, trackEvents.get(i), measures, analysis.measuresByStart, analysis.pickup, starts,
                    i == 0, i == 0 ? swing : null);
        }
        return doc;
    }

    // Where swing starts and how far through the beat its off-beat eighths are played, in percent
    private static class Swing {
        final long startTick;
        final int ratio;

        Swing(long startTick, int ratio) {
            this.startTick = startTick;
            this.ratio = ratio;
        }
    }

    // Shares of the beat that a swung off-beat eighth is played at. 2/3 is a triplet swing, and dotted rhythms (3/4)
    // are left alone
    private static final double SWING_FROM = 0.55;
    private static final double SWING_TO = 0.72;

    // Swing plays each off-beat eighth late. The music is swung if most beats with an off-beat note have only
    // that late eighth, and few have notes half way or on the other 16ths or triplets. Null if it's straight
    private Swing findSwing(List<List<MidiPlayback.Event>> trackEvents, LongUnaryOperator beatStart) {
        int swungBeats = 0;
        int evenBeats = 0;
        double swungTotal = 0;
        long firstSwung = Long.MAX_VALUE;
        for (List<MidiPlayback.Event> events : trackEvents) {
            TreeMap<Long, List<Double>> beats = new TreeMap<>();
            for (MidiPlayback.Event event : events) {
                long beat = beatStart.applyAsLong(event.tick);
                beats.computeIfAbsent(beat, k -> new ArrayList<>()).add((event.tick - beat) / (double) resolution);
            }
            for (Map.Entry<Long, List<Double>> beat : beats.entrySet()) {
                Double swung = getSwungShare(beat.getValue());
                if (swung != null) {
                    swungBeats++;
                    swungTotal += swung;
                    firstSwung = Math.min(firstSwung, beat.getKey());
                } else if (beat.getValue().stream().anyMatch(share -> share > 0.03)) {
                    evenBeats++;
                }
            }
        }
        if (swungBeats < 8 || swungBeats < 3 * evenBeats) {
            return null;
        }
        return new Swing(firstSwung, (int) Math.round(swungTotal / swungBeats * 100));
    }

    // The share of the beat its late off-beat eighth is at, if the beat's only other note is on the beat
    private static Double getSwungShare(List<Double> shares) {
        Double swung = null;
        for (double share : shares) {
            if (share >= SWING_FROM && share <= SWING_TO) {
                swung = share;
            } else if (share > 0.03) {
                return null;
            }
        }
        return swung;
    }

    // Measures from the start to the end of the last note, following the time signatures
    private List<Measure> getMeasures(List<MidiTrack> chosen) {
        // Until the last note stops, and far enough past the last start that snapping can't move it beyond
        // the last measure
        long lastNote = 0;
        for (MidiTrack track : chosen) {
            for (Note note : track.notes) {
                lastNote = Math.max(lastNote, Math.max(note.start + resolution / 16 + 1, note.end - resolution / 16));
            }
        }
        List<Measure> measures = new ArrayList<>();
        long tick = 0;
        while (tick < lastNote || measures.isEmpty()) {
            int[] timeSig = timeSigs.floorEntry(tick).getValue();
            long fullLength = Math.max(1, Math.round(timeSig[0] * 4.0 / timeSig[1] * resolution));
            Long nextChange = timeSigs.higherKey(tick);
            long end = nextChange == null ? tick + fullLength : Math.min(tick + fullLength, nextChange);
            measures.add(new Measure(tick, end - tick, timeSig));
            tick = end;
        }
        return measures;
    }

    // MuseScore exports a pickup as a first measure with its own short time signature, like 1/4 before 3/4
    private boolean hasPickup(List<Measure> measures) {
        if (measures.size() < 2) {
            return false;
        }
        Measure first = measures.get(0);
        Measure second = measures.get(1);
        long secondFullLength = Math.round(second.timeSig[0] * 4.0 / second.timeSig[1] * resolution);
        return first.timeSig != second.timeSig && timeSigs.containsKey(second.startTick) && first.lengthTicks < secondFullLength;
    }

    private long toUnits(long ticks) {
        return Math.round(ticks * (double) UNITS / resolution);
    }

    // Events of one track by start position in units, snapped to a straight or triplet grid per beat.
    // With swing, a late off-beat eighth is moved back to half way through the beat
    private TreeMap<Long, MidiPlayback.Event> snapEvents(List<MidiPlayback.Event> events, TreeMap<Long, Measure> measuresByStart,
                                                         TreeSet<Long> tripletBeats, boolean swing) {
        // Events by the beat (quarter note) they're nearest the start of, in ticks
        TreeMap<Long, List<MidiPlayback.Event>> byBeat = new TreeMap<>();
        long tolerance = resolution / 16;
        for (MidiPlayback.Event event : events) {
            long tick = event.tick + tolerance;
            Measure measure = measuresByStart.floorEntry(tick).getValue();
            long beatStart = measure.startTick + (tick - measure.startTick) / resolution * resolution;
            byBeat.computeIfAbsent(beatStart, k -> new ArrayList<>()).add(event);
        }

        TreeMap<Long, MidiPlayback.Event> chords = new TreeMap<>();
        for (Map.Entry<Long, List<MidiPlayback.Event>> beat : byBeat.entrySet()) {
            long beatStart = beat.getKey();
            List<MidiPlayback.Event> beatEvents = beat.getValue();
            if (swing) {
                List<Double> shares = beatEvents.stream().map(event -> (event.tick - beatStart) / (double) resolution)
                        .collect(Collectors.toList());
                if (getSwungShare(shares) != null) {
                    for (MidiPlayback.Event event : beatEvents) {
                        if (event.tick - beatStart > resolution * 0.03) {
                            event.tick = beatStart + resolution / 2;
                        }
                    }
                }
            }
            Measure measure = measuresByStart.floorEntry(beatStart).getValue();
            // A beat cut short by the end of the measure is always straight
            boolean fullBeat = beatStart + resolution <= measure.startTick + measure.lengthTicks;
            double straightError = 0;
            double tripletError = 0;
            for (MidiPlayback.Event event : beatEvents) {
                straightError += snapError(event.tick - beatStart, resolution / 8.0);
                tripletError += snapError(event.tick - beatStart, resolution / 6.0);
            }
            boolean triplet = fullBeat && tripletError < straightError - 1e-9;
            long beatUnits = toUnits(beatStart);
            if (triplet) {
                tripletBeats.add(beatUnits);
            }
            int grid = triplet ? UNITS / 6 : UNITS / 8;
            for (MidiPlayback.Event event : beatEvents) {
                double gridTicks = resolution / (triplet ? 6.0 : 8.0);
                long steps = Math.round((event.tick - beatStart) / gridTicks);
                long position = Math.max(0, beatUnits + steps * grid);
                MidiPlayback.Event existing = chords.putIfAbsent(position, event);
                if (existing != null) {
                    // Two events snapped together become one chord
                    existing.pitches.addAll(event.pitches);
                    existing.graceNotes.addAll(event.graceNotes);
                }
            }
        }
        return chords;
    }

    private static double snapError(double offset, double grid) {
        return Math.abs(offset - Math.round(offset / grid) * grid);
    }

    // A stretch of a measure written in one way: plain notes, triplets in one beat (base eighth),
    // or quarter-note triplets over two beats (base quarter)
    private static class Region {
        final long start;
        final long end;
        final String tupletBase; // null for plain notes

        Region(long start, long end, String tupletBase) {
            this.start = start;
            this.end = end;
            this.tupletBase = tupletBase;
        }
    }

    // Writes one track as a staff. The first staff gets the tempo markings, and the swing marking if swing is given
    private void writeStaff(Element staff, List<MidiPlayback.Event> events, List<Measure> measures,
                            TreeMap<Long, Measure> measuresByStart, boolean pickup, TreeSet<Integer> boxStarts,
                            boolean firstStaff, Swing swing) {
        Document doc = staff.getOwnerDocument();
        TreeSet<Long> tripletBeats = new TreeSet<>();
        TreeMap<Long, MidiPlayback.Event> chords = snapEvents(events, measuresByStart, tripletBeats, swing != null);

        int[] shownTimeSig = null;
        Integer shownKey = null;
        Long shownTempo = null;
        for (int m = 0; m < measures.size(); m++) {
            Measure measure = measures.get(m);
            Element measureElement = doc.createElement("Measure");
            Element voice = doc.createElement("voice");
            measureElement.appendChild(voice);
            staff.appendChild(measureElement);

            long start = toUnits(measure.startTick);
            long end = toUnits(measure.startTick + measure.lengthTicks);
            // A pickup is shown with the time signature after it, and keeps its own length
            int[] timeSig = pickup && m == 0 ? measures.get(1).timeSig : measure.timeSig;
            long fullLength = Math.round(timeSig[0] * 4.0 / timeSig[1] * UNITS);
            if (end - start != fullLength) {
                measureElement.setAttribute("len", fraction(end - start));
            }

            int key = keySigs.floorEntry(measure.startTick + measure.lengthTicks - 1).getValue();
            if (shownKey == null || key != shownKey) {
                Element keySig = doc.createElement("KeySig");
                appendText(keySig, "accidental", Integer.toString(key));
                voice.appendChild(keySig);
                shownKey = key;
            }
            if (shownTimeSig == null || timeSig[0] != shownTimeSig[0] || timeSig[1] != shownTimeSig[1]) {
                Element timeSigElement = doc.createElement("TimeSig");
                appendText(timeSigElement, "sigN", Integer.toString(timeSig[0]));
                appendText(timeSigElement, "sigD", Integer.toString(timeSig[1]));
                voice.appendChild(timeSigElement);
                shownTimeSig = timeSig;
            }
            // A tempo marking at the start, and where a new tempo lasts at least two measures,
            // not each step of a slowing down spread over many small changes
            long beatsPerMinute = Math.round(60_000_000.0 / tempos.floorEntry(measure.startTick).getValue());
            long steadyUntil = m + 2 <= measures.size() ? measures.get(m + 1).startTick + measures.get(m + 1).lengthTicks : 0;
            boolean steady = steadyUntil > 0 && tempos.subMap(measure.startTick, false, steadyUntil, false).isEmpty();
            if (firstStaff && (shownTempo == null || (steady && beatsPerMinute != shownTempo))) {
                Element tempo = doc.createElement("Tempo");
                appendText(tempo, "tempo", Double.toString(beatsPerMinute / 60.0));
                Element text = doc.createElement("text");
                text.appendChild(doc.createElement("sym")).setTextContent("metNoteQuarterUp");
                text.appendChild(doc.createTextNode(" = " + beatsPerMinute));
                tempo.appendChild(text);
                voice.appendChild(tempo);
                shownTempo = beatsPerMinute;
            }
            // Swing is marked like MuseScore's swing text, which labels the box it starts in
            if (swing != null && swing.startTick >= measure.startTick && swing.startTick < measure.startTick + measure.lengthTicks) {
                Element staffText = doc.createElement("StaffText");
                Element swingElement = doc.createElement("swing");
                swingElement.setAttribute("unit", "eighth");
                swingElement.setAttribute("ratio", Integer.toString(swing.ratio));
                staffText.appendChild(swingElement);
                voice.appendChild(staffText);
            }

            for (Region region : getRegions(start, end, tripletBeats, chords)) {
                writeRegion(voice, region, chords);
            }

            // A double bar line ends a box, like in MuseScore files
            if (boxStarts.contains(m + 1) && m < measures.size() - 1) {
                Element barLine = doc.createElement("BarLine");
                appendText(barLine, "subtype", "double");
                voice.appendChild(barLine);
            }
        }
    }

    // Splits a measure into plain and triplet stretches
    private List<Region> getRegions(long start, long end, TreeSet<Long> tripletBeats, TreeMap<Long, MidiPlayback.Event> chords) {
        List<Region> regions = new ArrayList<>();
        long plainStart = start;
        long beat = start;
        while (beat < end) {
            if (!tripletBeats.contains(beat) || beat + UNITS > end) {
                beat += UNITS;
                continue;
            }
            if (plainStart < beat) {
                regions.add(new Region(plainStart, beat, null));
            }
            // Two triplet beats whose notes are all on quarter-note triplets are written as one group
            boolean quarterTriplets = tripletBeats.contains(beat + UNITS) && beat + 2 * UNITS <= end;
            if (quarterTriplets) {
                boolean offQuarterTriplet = false;
                for (long position : chords.subMap(beat, beat + 2 * UNITS).keySet()) {
                    offQuarterTriplet |= (position - beat) % (2 * UNITS / 3) != 0;
                }
                quarterTriplets = !offQuarterTriplet;
            }
            if (quarterTriplets) {
                regions.add(new Region(beat, beat + 2 * UNITS, "quarter"));
                beat += 2 * UNITS;
                plainStart = beat;
                continue;
            }
            // Triplet 16ths in only one half of the beat are a group of three 16ths, and the other half is plain
            int half = UNITS / 2;
            boolean firstHalf = hasOffGridNotes(chords, beat, beat + half);
            boolean secondHalf = hasOffGridNotes(chords, beat + half, beat + UNITS);
            if (firstHalf && !secondHalf) {
                regions.add(new Region(beat, beat + half, "16th"));
                plainStart = beat + half;
            } else if (secondHalf && !firstHalf) {
                regions.add(new Region(beat, beat + half, null));
                regions.add(new Region(beat + half, beat + UNITS, "16th"));
                plainStart = beat + UNITS;
            } else {
                regions.add(new Region(beat, beat + UNITS, "eighth"));
                plainStart = beat + UNITS;
            }
            beat += UNITS;
        }
        if (plainStart < end) {
            regions.add(new Region(plainStart, end, null));
        }
        return regions;
    }

    // Whether any chord between the positions is off the plain 32nd-note grid, so it needs a triplet
    private static boolean hasOffGridNotes(TreeMap<Long, MidiPlayback.Event> chords, long from, long to) {
        for (long position : chords.subMap(from, to).keySet()) {
            if ((position - from) % (UNITS / 8) != 0) {
                return true;
            }
        }
        return false;
    }

    // Writes the chords and rests of one stretch. Each chord lasts until the next chord or the end of the stretch,
    // written as the chord followed by rests if no single note value fits
    private void writeRegion(Element voice, Region region, TreeMap<Long, MidiPlayback.Event> chords) {
        Document doc = voice.getOwnerDocument();
        // Triplets are written 3/2 as long as they last
        double writtenScale = region.tupletBase == null ? 1 : 1.5;
        if (region.tupletBase != null) {
            Element tuplet = doc.createElement("Tuplet");
            appendText(tuplet, "normalNotes", "2");
            appendText(tuplet, "actualNotes", "3");
            appendText(tuplet, "baseNote", region.tupletBase);
            voice.appendChild(tuplet);
        }
        List<Long> positions = new ArrayList<>(chords.subMap(region.start, region.end).keySet());
        if (positions.isEmpty() || positions.get(0) > region.start) {
            positions.add(0, region.start);
        }
        for (int i = 0; i < positions.size(); i++) {
            long position = positions.get(i);
            long next = i + 1 < positions.size() ? positions.get(i + 1) : region.end;
            MidiPlayback.Event event = chords.get(position);
            boolean first = true;
            for (String[] duration : splitDuration((next - position) * writtenScale / UNITS)) {
                boolean chord = first && event != null;
                Element element = ScoreXml.createDurationElement(voice, chord ? "Chord" : "Rest", duration);
                if (chord) {
                    writeChordDetails(element, event);
                }
                voice.appendChild(element);
                first = false;
            }
        }
        if (region.tupletBase != null) {
            voice.appendChild(doc.createElement("endTuplet"));
        }
    }

    // Adds an event's notes to its chord, with its grace notes, trill or tremolo and arpeggio marked the way
    // the rest of the converter reads them from MuseScore files
    private static void writeChordDetails(Element chord, MidiPlayback.Event event) {
        Document doc = chord.getOwnerDocument();
        if (event.arpeggio) {
            Element arpeggio = doc.createElement("Arpeggio");
            appendText(arpeggio, "subtype", "0");
            chord.appendChild(arpeggio);
        }
        for (int pitch : event.pitches) {
            Element note = doc.createElement("Note");
            appendText(note, "pitch", Integer.toString(pitch));
            chord.appendChild(note);
        }
        if (event.ornament != null) {
            chord.setAttribute(Ornaments.ORNAMENT, joinPitches(event.ornament));
        }
        if (!event.graceNotes.isEmpty()) {
            chord.setAttribute(Ornaments.GRACE_BEFORE, event.graceNotes.stream()
                    .map(MidiReader::joinPitches).collect(Collectors.joining(";")));
        }
    }

    private static String joinPitches(java.util.Collection<Integer> pitches) {
        return pitches.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    // A length in units as a fraction of a whole note, like "1/4"
    private static String fraction(long units) {
        long numerator = units;
        long denominator = UNITS * 4L;
        long divisor = gcd(numerator, denominator);
        return numerator / divisor + "/" + denominator / divisor;
    }

    private static long gcd(long a, long b) {
        return b == 0 ? Math.max(a, 1) : gcd(b, a % b);
    }

    private static void appendText(Element parent, String tagName, String text) {
        Element child = parent.getOwnerDocument().createElement(tagName);
        child.setTextContent(text);
        parent.appendChild(child);
    }
}

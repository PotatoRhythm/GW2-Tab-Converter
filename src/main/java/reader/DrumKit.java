package reader;

import java.util.Map;

// General MIDI drum sounds written for a drum kit played with the octave swap keys, like SongCraft's drum set:
// 1 bass drum, 2 second bass drum, 3 snare, 4 second snare, 5 cross stick, 6 and 7 ghost snares,
// 8 high tom, 9 mid tom, 0 low tom, #1 crash, #2 ride, #4 closed hi-hat, #5 open hi-hat, #6 pedal hi-hat.
// GW2's drum keys reach all of them, from the pitches below. Used for MIDI drum tracks and for MuseScore
// drum parts that aren't written for a GW2 drum already
public final class DrumKit {
    private DrumKit() {
    }

    // The GW2 instrument whose keys the kit is written with
    public static final String KEYS = "Drum";

    private static final int BASS = 60, BASS_2 = 62, SNARE = 64, SNARE_2 = 65, CROSS_STICK = 67,
            HIGH_TOM = 72, MID_TOM = 74, LOW_TOM = 76, CRASH = 61, RIDE = 63, HI_HAT = 66, OPEN_HI_HAT = 68,
            PEDAL_HI_HAT = 70;
    // The kit's pad for each General MIDI drum sound, by its note number. Sounds the kit doesn't have get the
    // pad that sounds most like them: hand drums are toms, small shakers are the closed hi-hat, wood and
    // metal clicks are the cross stick, and splashes and other cymbals are the crash
    private static final Map<Integer, Integer> GM_DRUMS = Map.ofEntries(
            Map.entry(35, BASS_2), Map.entry(36, BASS), Map.entry(37, CROSS_STICK), Map.entry(38, SNARE),
            Map.entry(39, SNARE_2), Map.entry(40, SNARE_2), Map.entry(41, LOW_TOM), Map.entry(42, HI_HAT),
            Map.entry(43, LOW_TOM), Map.entry(44, PEDAL_HI_HAT), Map.entry(45, MID_TOM), Map.entry(46, OPEN_HI_HAT),
            Map.entry(47, MID_TOM), Map.entry(48, HIGH_TOM), Map.entry(49, CRASH), Map.entry(50, HIGH_TOM),
            Map.entry(51, RIDE), Map.entry(52, CRASH), Map.entry(53, RIDE), Map.entry(54, HI_HAT),
            Map.entry(55, CRASH), Map.entry(56, CROSS_STICK), Map.entry(57, CRASH), Map.entry(58, CROSS_STICK),
            Map.entry(59, RIDE), Map.entry(60, HIGH_TOM), Map.entry(61, MID_TOM), Map.entry(62, HIGH_TOM),
            Map.entry(63, MID_TOM), Map.entry(64, LOW_TOM), Map.entry(65, HIGH_TOM), Map.entry(66, MID_TOM),
            Map.entry(67, CROSS_STICK), Map.entry(68, CROSS_STICK), Map.entry(69, HI_HAT), Map.entry(70, HI_HAT),
            Map.entry(71, CROSS_STICK), Map.entry(72, CROSS_STICK), Map.entry(73, HI_HAT), Map.entry(74, HI_HAT),
            Map.entry(75, CROSS_STICK), Map.entry(76, CROSS_STICK), Map.entry(77, CROSS_STICK),
            Map.entry(78, HIGH_TOM), Map.entry(79, MID_TOM), Map.entry(80, CROSS_STICK), Map.entry(81, CROSS_STICK),
            Map.entry(82, HI_HAT), Map.entry(83, HI_HAT), Map.entry(84, RIDE), Map.entry(85, CROSS_STICK),
            Map.entry(86, BASS), Map.entry(87, BASS_2));

    // The pitch that writes a General MIDI drum sound's pad with the kit's keys, or null if no pad is close
    public static Integer padPitch(int drumSound) {
        return GM_DRUMS.get(drumSound);
    }
}

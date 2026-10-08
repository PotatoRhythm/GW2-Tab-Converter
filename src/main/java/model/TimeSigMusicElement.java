package model;

import java.util.ArrayList;

public class TimeSigMusicElement extends MusicElement {
    private final int beats;
    private final int noteLength;

    public TimeSigMusicElement(ArrayList<Integer> noteList, double duration, int arpeggioType, int beats, int noteLength) {
        super(noteList, duration, arpeggioType);
        this.beats = beats;
        this.noteLength = noteLength;
    }

    public int getBeats() { return beats; }
    public int getNoteLength() { return noteLength; }
}

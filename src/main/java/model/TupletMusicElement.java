package model;

import java.util.ArrayList;

public class TupletMusicElement extends MusicElement {
    private final double normalNotes;
    private final double actualNotes;
    private final boolean isFirst;
    private final boolean isLast;

    public TupletMusicElement(ArrayList<Integer> noteList, double duration, int arpeggioType, double normalNotes, double actualNotes, boolean isFirst, boolean isLast) {
        super(noteList, duration, arpeggioType);
        this.normalNotes = normalNotes;
        this.actualNotes = actualNotes;
        this.isFirst = isFirst;
        this.isLast = isLast;
    }

    public boolean isFirst() {
        return isFirst;
    }

    public boolean isLast() {
        return isLast;
    }

    // How much faster than written the tuplet's notes are played, e.g. 2/3 for a triplet
    public double getTimeScale() {
        return normalNotes / actualNotes;
    }
}

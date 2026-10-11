package model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MusicElement {
    private final ArrayList<Integer> noteList;
    private final double duration;
    private final int arpeggioType;
    private List<List<Integer>> graceBefore = List.of();
    private List<List<Integer>> graceAfter = List.of();
    private boolean ornament = false;

    public MusicElement(ArrayList<Integer> noteList, double duration, int arpeggioType) {
        if (noteList != null) {
            this.noteList = noteList;
        } else {
            this.noteList = new ArrayList<>();
        }
        this.duration = duration;

        if (arpeggioType == -1) {
            this.arpeggioType = 0;
        }
        else if (arpeggioType == 2 || arpeggioType == 5) {
            if (noteList != null) {
                Collections.reverse(noteList);
            }
            this.arpeggioType = 1; // TODO: no need for type
        }
        else {
            this.arpeggioType = 1;
        }
    }

    public ArrayList<Integer> getNoteList() {
        return noteList;
    }

    public double getDuration() {
        return duration;
    }

    public int getArpeggioType() {
        return arpeggioType;
    }

    public List<List<Integer>> getGraceBefore() {
        return graceBefore;
    }

    public List<List<Integer>> getGraceAfter() {
        return graceAfter;
    }

    public void setGraceNotes(List<List<Integer>> before, List<List<Integer>> after) {
        this.graceBefore = before;
        this.graceAfter = after;
    }

    public boolean isOrnament() {
        return ornament;
    }

    public void setOrnament(boolean ornament) {
        this.ornament = ornament;
    }

    public boolean isRest() {
        return noteList.size() == 0;
    }
}

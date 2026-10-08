package model;

import java.util.ArrayList;
import java.util.Collections;

public class MusicElement {
    private final ArrayList<Integer> noteList;
    private final double duration;
    private final int arpeggioType;

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

    public boolean isRest() {
        return noteList.size() == 0;
    }
}

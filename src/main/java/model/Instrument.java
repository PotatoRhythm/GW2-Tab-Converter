package model;

import java.util.ArrayList;

public class Instrument {
    private final String name;
    // The GW2 instrument whose keys the notes are written with; usually the same as the name
    private final String keys;
    private double longestRow = 0;
    private final ArrayList<ArrayList<MusicElement>> lines;
    private final ArrayList<LineTiming> lineTimings;

    public Instrument(String name, ArrayList<ArrayList<MusicElement>> lines, ArrayList<LineTiming> lineTimings) {
        this(name, name, lines, lineTimings);
    }

    public Instrument(String name, String keys, ArrayList<ArrayList<MusicElement>> lines, ArrayList<LineTiming> lineTimings) {
        this.name = name;
        this.keys = keys;
        this.lines = lines;
        this.lineTimings = lineTimings;
    }

    public void updateColumnWidth(double length) {
        if (length > longestRow) {
            longestRow = length;
        }
    }

    public String getName() { return name; }
    public String getKeys() { return keys; }
    public double getLongestRow() { return longestRow; }

    public ArrayList<ArrayList<MusicElement>> getStaff() {
        return lines;
    }

    public ArrayList<LineTiming> getLineTimings() {
        return lineTimings;
    }
}

package model;

import java.util.ArrayList;
import java.util.List;

public class Box {
    private final int numMeasures;
    // Text written at the top of the box, like "Swing", or null
    private String label;
    private final List<Marking> markings = new ArrayList<>();

    // A tempo like "♩120" and a time signature like "3/4" 
    public static class Marking {
        private final int line;
        private String tempo;
        private String timeSignature;

        public Marking(int line) {
            this.line = line;
        }

        // The line of the box, counted from 0
        public int getLine() {
            return line;
        }
        public String getTempo() {
            return tempo;
        }
        public void setTempo(String tempo) {
            this.tempo = tempo;
        }
        public String getTimeSignature() {
            return timeSignature;
        }
        public void setTimeSignature(String timeSignature) {
            this.timeSignature = timeSignature;
        }
    }

    public Box(int numMeasures) {
        this.numMeasures = numMeasures;
    }

    // Getters
    public int getNumMeasures() {
        return numMeasures;
    }
    public int getNumLines(int measuresPerRow) {
        return (int) Math.ceil((double) numMeasures / measuresPerRow);
    }
    public String getLabel() {
        return label;
    }
    public void setLabel(String label) {
        this.label = label;
    }
    // In line order
    public List<Marking> getMarkings() {
        return markings;
    }
    // The marking on a line, added if there isn't one yet
    public Marking getMarking(int line) {
        for (Marking marking : markings) {
            if (marking.getLine() == line) {
                return marking;
            }
        }
        Marking marking = new Marking(line);
        markings.add(marking);
        return marking;
    }
}

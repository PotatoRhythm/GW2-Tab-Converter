package model;

public class Box {
    private final int numMeasures;

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
}
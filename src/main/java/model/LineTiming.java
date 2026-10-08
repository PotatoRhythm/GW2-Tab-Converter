package model;

// Where each measure of a tab line starts, so beats can be counted correctly even when
// a measure is shorter than its time signature (like a pickup)
public class LineTiming {
    private static final double EPSILON = 1e-6;

    private final double firstBeat;
    private final double[] measureLengths;

    // firstBeat is the beat the line starts on: 1, or later for a pickup measure
    public LineTiming(double firstBeat, double[] measureLengths) {
        this.firstBeat = firstBeat;
        this.measureLengths = measureLengths;
    }

    // Beat within its measure (1 = downbeat) at a position in quarter notes from the start of the line
    public double getBeat(double position) {
        double measureStart = 0;
        for (int i = 0; i < measureLengths.length; i++) {
            if (position < measureStart + measureLengths[i] - EPSILON) {
                double measureFirstBeat = i == 0 ? firstBeat : 1;
                return measureFirstBeat + position - measureStart;
            }
            measureStart += measureLengths[i];
        }
        double lastLength = measureLengths.length > 0 ? measureLengths[measureLengths.length - 1] : 4;
        return 1 + (position - measureStart) % lastLength;
    }
}

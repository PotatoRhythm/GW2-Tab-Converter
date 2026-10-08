package tab;

import model.LineTiming;

// Writes how long each note or rest lasts with the beat highlights
class RhythmWriter {
    private final TabWriter tabWriter;

    RhythmWriter(TabWriter tabWriter) {
        this.tabWriter = tabWriter;
    }

    // Writes how long a note or rest lasts after it starts at the given position in the line.
    // Lengths are written as notated. TimeScale converts them to real time for tuplets
    String write(double duration, boolean note, LineTiming timing, double startPosition, double quartersPerMeasure,
                 double secondBeat, double timeScale) {
        StringBuilder restBuilder = new StringBuilder();
        double position = startPosition;
        double beatsRemaining = duration;
        String prevRest = "NA";

        while(beatsRemaining > 0) {
            double currentBeat = timing.getBeat(position);
            // Closing tags for whichever beat highlight was opened this step
            String closer = "";

            // Quarter note
            if (beatsRemaining >= 1) {
                if (currentBeat == quartersPerMeasure + 0.25) {
                    restBuilder.append("- -.");
                    prevRest = "NA";
                    position = advance(position, 0.75, timeScale);
                    beatsRemaining -= 0.75;
                    continue;
                } if (currentBeat == quartersPerMeasure + 0.5) {
                    restBuilder.append("- -");
                    prevRest = "8th";
                    position = advance(position, 0.5, timeScale);
                    beatsRemaining -= 0.5;
                    continue;
                }

                if ((currentBeat == 1 || note && currentBeat >= quartersPerMeasure + 0.25 && currentBeat < quartersPerMeasure + 1)) {
                    restBuilder.append(" <!--Ignore This-->").append(tabWriter.writePrimaryBeatOpener()).append("~");
                    closer = tabWriter.writePrimaryBeatCloser();
                } else if ((currentBeat == secondBeat || note && currentBeat >= secondBeat - 0.75 && currentBeat < secondBeat)) {
                    restBuilder.append(" <!--Ignore This-->").append(tabWriter.writeSecondaryBeatOpener()).append("~");
                    closer = tabWriter.writeSecondaryBeatCloser();
                } else {
                    restBuilder.append(" <!--Ignore This-->~");
                }

                prevRest = "Quarter";
                position = advance(position, 1, timeScale);
                beatsRemaining -= 1;
            }
            // Eighth note
            else if (beatsRemaining >= 0.5) {
                if (prevRest.equals("Quarter")) {
                    if (currentBeat == 1 || note && currentBeat > 1 && currentBeat <= 1.5) {
                        restBuilder.append(tabWriter.writePrimaryBeatOpener()).append(".");
                        closer = tabWriter.writePrimaryBeatCloser();
                    } else if (currentBeat == secondBeat || (note && currentBeat >= secondBeat && currentBeat < secondBeat + 0.5)) {
                        restBuilder.append(tabWriter.writeSecondaryBeatOpener()).append(".");
                        closer = tabWriter.writeSecondaryBeatCloser();
                    } else {
                        restBuilder.append(".");
                    }
                    prevRest = "NA";
                } else if ((!note || duration >= 1)) {
                    if (currentBeat == 1) {
                        restBuilder.append(tabWriter.writePrimaryBeatOpener()).append("- -");
                        closer = tabWriter.writePrimaryBeatCloser();
                    } else if (currentBeat == secondBeat) {
                        restBuilder.append(tabWriter.writeSecondaryBeatOpener()).append("- -");
                        closer = tabWriter.writeSecondaryBeatCloser();
                    } else {
                        restBuilder.append("- -");
                    }
                    prevRest = "8th";
                } else {
                    prevRest = "8th";
                }
                position = advance(position, 0.5, timeScale);
                beatsRemaining -= 0.5;
            }
            // 16th note
            else if (beatsRemaining >= 0.25) {
                if (prevRest.equals("8th")) {
                    if (currentBeat == 1 || note && currentBeat > 1 && currentBeat <= 1.25) {
                        restBuilder.append(tabWriter.writePrimaryBeatOpener()).append(".");
                        closer = tabWriter.writePrimaryBeatCloser();
                    } else if ((currentBeat == secondBeat || (note && currentBeat > secondBeat && currentBeat <= secondBeat + 0.25))) {
                        restBuilder.append(tabWriter.writeSecondaryBeatOpener()).append(".");
                        closer = tabWriter.writeSecondaryBeatCloser();
                    } else {
                        restBuilder.append(".");
                    }
                    prevRest = "NA";
                } else if (!note || duration >= 0.5) {
                    if (currentBeat == 1.0) {
                        restBuilder.append(tabWriter.writePrimaryBeatOpener()).append("-");
                        closer = tabWriter.writePrimaryBeatCloser();
                    } else if (currentBeat == secondBeat) {
                        restBuilder.append(tabWriter.writeSecondaryBeatOpener()).append("-");
                        closer = tabWriter.writeSecondaryBeatCloser();
                    } else {
                        restBuilder.append("-");
                    }
                    prevRest = "16th";
                } else {
                    prevRest = "16th";
                }
                position = advance(position, 0.25, timeScale);
                beatsRemaining -= 0.25;
            }
            // TODO: Deal with these durations
            // 32nd note
            else if (beatsRemaining >= 0.125) {
                position = advance(position, 0.125, timeScale);
                beatsRemaining -= 0.125;
            }
            // 64th note
            else if (beatsRemaining >= 0.0625) {
                position = advance(position, 0.0625, timeScale);
                beatsRemaining -= 0.0625;
            }
            // Anything shorter just uses up what's left
            else {
                position = advance(position, beatsRemaining, timeScale);
                beatsRemaining = 0;
            }
            restBuilder.append(closer);
        }
        // Remove first space
        restBuilder.replace(0, restBuilder.length(), restBuilder.toString().replaceFirst("\\s<!--Ignore This-->", ""));

        return restBuilder.toString();
    }

    // Moves a position in the line forward by a written length, scaled for tuplets. 
    static double advance(double position, double writtenLength, double timeScale) {
        double next = position + writtenLength * timeScale;
        double snapped = Math.round(next * 4096) / 4096.0;
        return Math.abs(next - snapped) < 1e-5 ? snapped : next;
    }
}

package app;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;

// Plays a MIDI file from a chosen tick, so measures can be found by ear
final class MeasurePlayer {
    private volatile Sequence sequence;
    private Sequencer sequencer;
    // Set while the sequencer is being opened or started, which can take a moment the first time
    private volatile boolean starting;
    private boolean closed;

    MeasurePlayer(Sequence sequence) {
        this.sequence = sequence;
    }

    // Starts playing from a tick, in the background. onError gets the message if it can't play
    void play(long tick, java.util.function.Consumer<Exception> onError) {
        starting = true;
        Thread thread = new Thread(() -> {
            try {
                synchronized (this) {
                    if (closed) {
                        return;
                    }
                    if (sequencer == null) {
                        sequencer = MidiSystem.getSequencer();
                        sequencer.open();
                    }
                    if (sequencer.getSequence() != sequence) {
                        sequencer.setSequence(sequence);
                    }
                    sequencer.stop();
                    sequencer.setTickPosition(tick);
                    sequencer.start();
                }
            } catch (Exception e) {
                onError.accept(e);
            } finally {
                starting = false;
            }
        }, "Measure player");
        thread.setDaemon(true);
        thread.start();
    }

    // What to play from the next play on
    void setSequence(Sequence sequence) {
        this.sequence = sequence;
    }

    boolean isStarting() {
        return starting;
    }

    boolean isPlaying() {
        return starting || (sequencer != null && sequencer.isOpen() && sequencer.isRunning());
    }

    long getTick() {
        return sequencer == null ? 0 : sequencer.getTickPosition();
    }

    synchronized void stop() {
        if (sequencer != null && sequencer.isOpen()) {
            sequencer.stop();
        }
    }

    synchronized void close() {
        closed = true;
        if (sequencer != null) {
            sequencer.close();
        }
    }
}

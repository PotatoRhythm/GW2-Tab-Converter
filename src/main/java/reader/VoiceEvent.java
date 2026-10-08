package reader;

import org.w3c.dom.Element;

// A Chord or Rest and where it falls in its measure, in quarter notes
class VoiceEvent {
    final Element element;
    final double onset;
    final double length;
    final int tupletIndex; // which top-level tuplet of its voice it is in, or -1

    VoiceEvent(Element element, double onset, double length, int tupletIndex) {
        this.element = element;
        this.onset = onset;
        this.length = length;
        this.tupletIndex = tupletIndex;
    }

    boolean isNote() {
        return element.getTagName().equals("Chord") && length > 0;
    }
}

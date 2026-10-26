package org.openpnp.model;

import java.util.Locale;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

/**
 * Advice on the pick rotation a feeder should have: the orientation of the part's footprint zero
 * as it sits in the feeder, in machine coordinates. The operator reviews and applies it.
 */
public class RotationSuggestion {
    public enum Confidence {
        High,
        Medium,
        Low
    }

    @Attribute
    private double rotation;

    @Attribute
    private Confidence confidence;

    @Element(required = false)
    private String rationale;

    /** For Simple XML. */
    RotationSuggestion() {
    }

    public RotationSuggestion(double rotation, Confidence confidence, String rationale) {
        if (rationale != null) {
            XmlText.requireValid(rationale, "The rationale");
        }
        this.rotation = rotation;
        this.confidence = confidence;
        this.rationale = rationale;
    }

    public double getRotation() {
        return rotation;
    }

    public Confidence getConfidence() {
        return confidence;
    }

    public String getRationale() {
        return rationale;
    }

    /** What the Feeders table searches and sorts by. */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%.1f° (%s)", rotation, confidence);
    }
}

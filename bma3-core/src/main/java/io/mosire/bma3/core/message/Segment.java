package io.mosire.bma3.core.message;

import java.util.Objects;

/** One normalized message segment. */
public record Segment(String type, String text) {

    public Segment {
        Objects.requireNonNull(type, "type");
        text = text == null ? "" : text;
    }
}

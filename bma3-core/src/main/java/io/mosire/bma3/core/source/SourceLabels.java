package io.mosire.bma3.core.source;

import java.util.ArrayList;
import java.util.List;

/** Platform-aware labeler registry; unknown platforms fall back to the opaque source. */
public final class SourceLabels {

    private final List<SourceLabeler> labelers;

    private SourceLabels(List<SourceLabeler> labelers) {
        this.labelers = List.copyOf(labelers);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SourceLabels empty() {
        return new SourceLabels(List.of());
    }

    public String label(SourceAddress source) {
        for (SourceLabeler labeler : labelers) {
            if (!labeler.supports(source.platform())) {
                continue;
            }
            try {
                String label = labeler.label(source);
                if (label != null && !label.isBlank()) {
                    return label;
                }
            } catch (RuntimeException ignored) {
                // A broken adapter labeler must not break the panel; use the generic fallback.
            }
        }
        return source.displayFallback();
    }

    public SourceView view(SourceAddress source) {
        return SourceView.of(source, label(source));
    }

    public static final class Builder {

        private final List<SourceLabeler> labelers = new ArrayList<>();

        public Builder register(SourceLabeler labeler) {
            labelers.add(labeler);
            return this;
        }

        public SourceLabels build() {
            return new SourceLabels(labelers);
        }
    }
}

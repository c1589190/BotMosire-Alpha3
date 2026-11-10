package io.mosire.bma3.app.priority;

import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.core.priority.PrioritySignals;
import java.util.function.Supplier;

/**
 * Generic source priority: platform default + source override + user note + adapter signals,
 * compared against the global or per-source notification threshold.
 */
public final class PriorityResolver {

    private final Supplier<AppConfig.Weighting> weighting;

    public PriorityResolver(Supplier<AppConfig.Weighting> weighting) {
        this.weighting = weighting;
    }

    public PriorityDecision resolve(String platform, Integer sourceWeight,
                                    Integer sourceThreshold, Integer notePriority,
                                    PrioritySignals signals) {
        AppConfig.Weighting current = weighting.get();
        int base = current.platformWeight(platform);
        int boost = 0;
        if (signals != null) {
            if (signals.mention()) {
                boost = Math.max(boost, current.signals().mention());
            }
            if (signals.reply()) {
                boost = Math.max(boost, current.signals().reply());
            }
            if (signals.name()) {
                boost = Math.max(boost, current.signals().name());
            }
            if (signals.command()) {
                boost = Math.max(boost, current.signals().command());
            }
        }
        int priority = base;
        if (sourceWeight != null) {
            priority = Math.max(priority, sourceWeight);
        }
        if (notePriority != null) {
            priority = Math.max(priority, notePriority);
        }
        if (boost > 0) {
            priority = Math.max(priority, base + boost);
        }
        int threshold = sourceThreshold == null ? current.notifyThreshold() : sourceThreshold;
        return new PriorityDecision(priority, priority >= threshold, threshold, boost);
    }
}

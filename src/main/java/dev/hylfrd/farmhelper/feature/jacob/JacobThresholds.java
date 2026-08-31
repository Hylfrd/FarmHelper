package dev.hylfrd.farmhelper.feature.jacob;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/** Immutable crop-to-cap values supplied by a composition layer, without shared config coupling. */
public record JacobThresholds(Map<JacobCrop, Long> thresholds) {
    public JacobThresholds {
        Objects.requireNonNull(thresholds, "thresholds");
        EnumMap<JacobCrop, Long> copied = new EnumMap<>(JacobCrop.class);
        for (Map.Entry<JacobCrop, Long> entry : thresholds.entrySet()) {
            JacobCrop crop = Objects.requireNonNull(entry.getKey(), "threshold crop");
            Long threshold = Objects.requireNonNull(entry.getValue(), "threshold value");
            if (threshold < 0L) {
                throw new IllegalArgumentException("threshold must be non-negative");
            }
            copied.put(crop, threshold);
        }
        thresholds = Collections.unmodifiableMap(copied);
    }

    public static JacobThresholds of(JacobCrop crop, long threshold) {
        Objects.requireNonNull(crop, "crop");
        return new JacobThresholds(Map.of(crop, threshold));
    }

    public static JacobThresholds uniform(long threshold) {
        EnumMap<JacobCrop, Long> values = new EnumMap<>(JacobCrop.class);
        for (JacobCrop crop : JacobCrop.values()) {
            values.put(crop, threshold);
        }
        return new JacobThresholds(values);
    }

    public OptionalLong thresholdFor(JacobCrop crop) {
        Objects.requireNonNull(crop, "crop");
        Long threshold = thresholds.get(crop);
        return threshold == null ? OptionalLong.empty() : OptionalLong.of(threshold);
    }
}

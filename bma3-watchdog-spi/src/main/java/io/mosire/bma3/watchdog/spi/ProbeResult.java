package io.mosire.bma3.watchdog.spi;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One probe result. {@code facts} is a small, content-free map (ids, counts, ages) used by the
 * status tool and logs; it must never contain tokens, URLs, message bodies or file paths.
 */
public record ProbeResult(
        String component,
        Health health,
        String code,
        String detail,
        Instant at,
        Map<String, String> facts) {

    public ProbeResult {
        component = component == null ? "" : component;
        health = health == null ? Health.UNKNOWN : health;
        code = code == null ? "" : code;
        detail = detail == null ? "" : detail;
        at = at == null ? Instant.now() : at;
        facts = facts == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(facts));
    }

    public static ProbeResult up(String component, String detail) {
        return new ProbeResult(component, Health.UP, "OK", detail, Instant.now(), Map.of());
    }

    public static ProbeResult up(String component, String detail, Map<String, String> facts) {
        return new ProbeResult(component, Health.UP, "OK", detail, Instant.now(), facts);
    }

    public static ProbeResult degraded(String component, String code, String detail) {
        return new ProbeResult(component, Health.DEGRADED, code, detail, Instant.now(), Map.of());
    }

    public static ProbeResult degraded(String component, String code, String detail,
                                       Map<String, String> facts) {
        return new ProbeResult(component, Health.DEGRADED, code, detail, Instant.now(), facts);
    }

    public static ProbeResult down(String component, String code, String detail) {
        return new ProbeResult(component, Health.DOWN, code, detail, Instant.now(), Map.of());
    }

    public static ProbeResult down(String component, String code, String detail,
                                   Map<String, String> facts) {
        return new ProbeResult(component, Health.DOWN, code, detail, Instant.now(), facts);
    }

    public static ProbeResult unknown(String component, String detail) {
        return new ProbeResult(component, Health.UNKNOWN, "UNKNOWN", detail, Instant.now(), Map.of());
    }
}

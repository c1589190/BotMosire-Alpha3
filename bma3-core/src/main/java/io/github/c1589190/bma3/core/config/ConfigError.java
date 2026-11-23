package io.github.c1589190.bma3.core.config;

/** One validation error with a stable field path. */
public record ConfigError(String field, String message) {
}

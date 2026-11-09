package io.mosire.bma3.core.config;

import java.nio.file.Path;

/** Where a configuration snapshot came from. */
public record ConfigSource(Path file, boolean exists) {
}

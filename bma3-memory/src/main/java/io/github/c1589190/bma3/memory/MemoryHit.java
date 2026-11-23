package io.github.c1589190.bma3.memory;

import java.time.Instant;

/** Search row: a digest and an id, never the body — the Agent asks for {@code memory_view}. */
public record MemoryHit(long id, String digest, String tags, double weight, Instant createdAt) {
}

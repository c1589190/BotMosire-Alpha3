package io.mosire.bma3.core.source;

/** Supplies a human-readable label for one platform's source addresses. */
public interface SourceLabeler {

    boolean supports(String platform);

    /**
     * Returns a non-blank label such as {@code QQ群 955432692}. Implementations should not
     * throw for unknown route shapes; the registry provides a generic fallback.
     */
    String label(SourceAddress source);
}

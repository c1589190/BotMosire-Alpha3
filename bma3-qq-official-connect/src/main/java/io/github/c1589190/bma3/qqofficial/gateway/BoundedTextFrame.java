package io.github.c1589190.bma3.qqofficial.gateway;

/**
 * Bounded accumulator for fragmented text frames.
 *
 * <p>Only a count is checked; frame content is never included in the exception or logs.</p>
 */
final class BoundedTextFrame {

    private final int maxChars;
    private final StringBuilder buffer;

    BoundedTextFrame(int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.maxChars = maxChars;
        this.buffer = new StringBuilder(Math.min(maxChars, 8_192));
    }

    /**
     * Appends a fragment and returns the complete frame on {@code last}, or null while fragmented.
     *
     * @throws GatewayFrameTooLargeException when the accumulated text would exceed the bound
     */
    String append(CharSequence fragment, boolean last) {
        CharSequence value = fragment == null ? "" : fragment;
        if (value.length() > maxChars - buffer.length()) {
            buffer.setLength(0);
            throw new GatewayFrameTooLargeException();
        }
        buffer.append(value);
        if (!last) {
            return null;
        }
        String complete = buffer.toString();
        buffer.setLength(0);
        return complete;
    }
}

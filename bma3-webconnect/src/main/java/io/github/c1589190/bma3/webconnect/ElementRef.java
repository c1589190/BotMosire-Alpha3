package io.github.c1589190.bma3.webconnect;

/** Visible element reference returned by a snapshot. */
public record ElementRef(
        String ref,
        String tag,
        String text,
        String href,
        String type,
        int x,
        int y,
        int width,
        int height) {
}

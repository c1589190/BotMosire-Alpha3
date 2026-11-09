package io.mosire.bma3.webconnect;

import java.nio.file.Path;
import java.util.List;

/** Minimal browser session seam so AgentTools can be tested without Chrome. */
public interface BrowserSession extends AutoCloseable {

    void open(String url);

    String title();

    String currentUrl();

    List<ElementRef> snapshot();

    void click(String ref);

    void type(String ref, String text);

    String text();

    Path screenshot(Path directory);

    /** Duration of the first visible HTML5 video, or 0 when absent. */
    default double videoDuration() {
        throw new UnsupportedOperationException("video frames not supported");
    }

    /** Seek to seconds and save a frame. */
    default Path frameAt(double seconds, Path directory) {
        throw new UnsupportedOperationException("video frames not supported");
    }

    @Override
    void close();
}

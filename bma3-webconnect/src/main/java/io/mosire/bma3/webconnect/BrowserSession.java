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

    @Override
    void close();
}

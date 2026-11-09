package io.mosire.bma3.webconnect;

import io.mosire.agentlib.tool.AgentTool;
import java.util.List;

/** Lazily creates one Chromium session and exposes its AgentTools. */
public final class BrowserService implements AutoCloseable {

    private final BrowserOptions options;
    private SeleniumBrowserSession session;

    public BrowserService(BrowserOptions options) {
        this.options = options;
    }

    public synchronized BrowserSession session() {
        if (session == null) {
            session = new SeleniumBrowserSession(options);
        }
        return session;
    }

    public List<AgentTool> tools() {
        return BrowserTools.all(new BrowserToolContext(this::session,
                options.screenshotDir(), options.allowedHosts()));
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            session.close();
            session = null;
        }
    }
}

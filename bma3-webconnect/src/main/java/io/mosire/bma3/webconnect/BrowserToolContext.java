package io.mosire.bma3.webconnect;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Dependencies shared by browser AgentTools. */
public record BrowserToolContext(
        Supplier<BrowserSession> session,
        Path screenshotDir,
        List<String> allowedHosts) {

    public BrowserToolContext {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }
}

package io.mosire.bma3.webconnect;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Browser process options; credentials stay in the Chromium profile, not here. */
public record BrowserOptions(
        String binary,
        boolean headless,
        Path userDataDir,
        Path screenshotDir,
        List<String> allowedHosts,
        Duration pageLoadTimeout) {

    public BrowserOptions {
        binary = binary == null || binary.isBlank() ? "/snap/bin/chromium" : binary;
        userDataDir = userDataDir == null ? Path.of("data/browser") : userDataDir;
        screenshotDir = screenshotDir == null ? Path.of("data/screenshots") : screenshotDir;
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        pageLoadTimeout = pageLoadTimeout == null ? Duration.ofSeconds(30) : pageLoadTimeout;
    }
}

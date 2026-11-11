package io.mosire.bma3.app.tools;

import io.mosire.bma3.app.config.AppConfig;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/** Dependencies for the config read/write tools. */
public record ConfigToolContext(Path root, Map<String, String> env,
                                Supplier<AppConfig> effective) {

    public ConfigToolContext {
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}

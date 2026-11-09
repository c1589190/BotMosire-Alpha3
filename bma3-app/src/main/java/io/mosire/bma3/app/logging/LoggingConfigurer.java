package io.mosire.bma3.app.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

/** Applies Log4j2 levels without rebuilding the appender tree. */
public final class LoggingConfigurer {

    private LoggingConfigurer() {
    }

    public static void applyLogLevel(String module, Level level) {
        Configurator.setLevel(module == null || module.isBlank() ? "io.mosire.bma3" : module, level);
    }

    public static void applyRootLevel(String level) {
        Configurator.setRootLevel(Level.valueOf(level.toUpperCase()));
    }
}

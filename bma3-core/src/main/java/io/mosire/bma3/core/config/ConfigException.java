package io.mosire.bma3.core.config;

import java.util.List;

/** Configuration failed validation or interpolation. */
public class ConfigException extends RuntimeException {

    private final List<ConfigError> errors;

    public ConfigException(List<ConfigError> errors) {
        super(errors.stream()
                .map(error -> error.field() + ": " + error.message())
                .reduce((left, right) -> left + "; " + right)
                .orElse("配置错误"));
        this.errors = List.copyOf(errors);
    }

    public ConfigException(String field, String message) {
        this(List.of(new ConfigError(field, message)));
    }

    public List<ConfigError> errors() {
        return errors;
    }
}

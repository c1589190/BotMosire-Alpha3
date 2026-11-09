package io.mosire.bma3.core.config;

import java.util.Map;

/** A secret that must be resolved from the environment at use time, never persisted. */
public record SecretRef(String envName) {

    public String resolve(Map<String, String> env) {
        String value = env.get(envName);
        if (value == null || value.isBlank()) {
            throw new ConfigException("secret." + envName, "环境变量未设置");
        }
        return value;
    }
}

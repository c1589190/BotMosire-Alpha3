package io.mosire.bma3.core.account;

/** Identity of one Agent consumer (read cursor / inbox owner). */
public record ConsumerId(String value) {

    public ConsumerId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("consumerId 不能为空");
        }
    }
}

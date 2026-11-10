package io.mosire.bma3.app.config;

/** Mutable-at-runtime subset of the config; swapped atomically by ConfigWatcher. */
public record RuntimePolicy(
        AppConfig.Inbox.Display display,
        AppConfig.QqConnect.Send send,
        AppConfig.Weighting weighting) {

    public static RuntimePolicy from(AppConfig config) {
        return new RuntimePolicy(config.inbox().display(), config.qqconnect().send(),
                config.weighting());
    }
}

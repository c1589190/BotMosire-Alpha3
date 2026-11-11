package io.mosire.bma3.app.config;

/** Mutable-at-runtime subset of the config; swapped atomically by ConfigWatcher. */
public record RuntimePolicy(
        AppConfig.Inbox.Display display,
        AppConfig.Inbox.Push push,
        AppConfig.Inbox.Retention retention,
        AppConfig.QqConnect.Send send,
        AppConfig.QqConnect.Media media,
        AppConfig.Weighting weighting) {

    public static RuntimePolicy from(AppConfig config) {
        return new RuntimePolicy(config.inbox().display(), config.inbox().push(),
                config.inbox().retention(), config.qqconnect().send(),
                config.qqconnect().media(), config.weighting());
    }
}

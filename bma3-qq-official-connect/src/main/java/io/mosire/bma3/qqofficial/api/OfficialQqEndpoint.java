package io.mosire.bma3.qqofficial.api;

/** The fixed official endpoints used by this phase. Paths are not caller controlled. */
public enum OfficialQqEndpoint {
    TOKEN("POST", "/app/getAppAccessToken", false),
    USERS_ME("GET", "/users/@me", true),
    GATEWAY_BOT("GET", "/gateway/bot", true),
    GROUP_INFO("GET", "/v2/groups/%s/info", true),
    GROUP_BOT_STATE("GET", "/v2/groups/%s/bot_state", true),
    USER_INFO("GET", "/v2/users/%s/info", true),
    GENERATE_URL_LINK("POST", "/v2/generate_url_link", true),
    GROUP_MESSAGES("POST", "/v2/groups/%s/messages", false),
    USER_MESSAGES("POST", "/v2/users/%s/messages", false),
    GROUP_FILES("POST", "/v2/groups/%s/files", false),
    USER_FILES("POST", "/v2/users/%s/files", false);

    private final String method;
    private final String pathTemplate;
    private final boolean safeToRepeatAfterTokenRefresh;

    OfficialQqEndpoint(String method, String pathTemplate, boolean safeToRepeatAfterTokenRefresh) {
        this.method = method;
        this.pathTemplate = pathTemplate;
        this.safeToRepeatAfterTokenRefresh = safeToRepeatAfterTokenRefresh;
    }

    public String method() {
        return method;
    }

    public String pathTemplate() {
        return pathTemplate;
    }

    /**
     * True only where repeating the same request cannot duplicate an externally visible action.
     * POST generate_url_link is explicitly in that class for this phase; future message-send
     * endpoints must return false.
     */
    public boolean safeToRepeatAfterTokenRefresh() {
        return safeToRepeatAfterTokenRefresh;
    }

    /** Stable endpoint category for logs and metrics; never include a raw URL or openid. */
    public String category() {
        return name();
    }
}

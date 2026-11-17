package io.mosire.bma3.qqofficial.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.bma3.qqofficial.send.OfficialQqSendMessage;
import io.mosire.bma3.qqofficial.send.OfficialQqSendTransport;
import io.mosire.bma3.qqofficial.send.OfficialQqTransportException;
import java.util.Objects;

/**
 * Fixed-endpoint send adapter over {@link OfficialQqApiClient}.
 *
 * <p>The API client owns authentication and the no-write-retry rule. This adapter only chooses the
 * group/user endpoint and turns a successful response without a platform message id into an
 * ambiguous typed failure. Local request validation is the only path that is provably
 * not-dispatched.</p>
 */
public final class OfficialQqApiSendTransport implements OfficialQqSendTransport {

    private final OfficialQqApiClient api;
    private final String accountId;

    public OfficialQqApiSendTransport(OfficialQqApiClient api) {
        this.api = Objects.requireNonNull(api, "api");
        this.accountId = api.accountId();
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("api accountId 不能为空");
        }
    }

    @Override
    public String accountId() {
        return accountId;
    }

    @Override
    public String sendGroup(String groupOpenid, OfficialQqSendMessage message)
            throws OfficialQqTransportException {
        Objects.requireNonNull(message, "message");
        try {
            JsonNode response = api.sendGroupText(
                    groupOpenid, message.content(), message.msgId(), message.eventId(),
                    message.msgSeq());
            return requireExternalId(response);
        } catch (IllegalArgumentException invalid) {
            throw OfficialQqTransportException.notDispatched("INVALID_REQUEST");
        } catch (OfficialQqApiException failure) {
            throw classifyApiFailure(failure);
        }
    }

    @Override
    public String sendUser(String userOpenid, OfficialQqSendMessage message)
            throws OfficialQqTransportException {
        Objects.requireNonNull(message, "message");
        try {
            JsonNode response = api.sendUserText(
                    userOpenid, message.content(), message.msgId(), message.eventId(),
                    message.msgSeq());
            return requireExternalId(response);
        } catch (IllegalArgumentException invalid) {
            throw OfficialQqTransportException.notDispatched("INVALID_REQUEST");
        } catch (OfficialQqApiException failure) {
            throw classifyApiFailure(failure);
        }
    }

    private static OfficialQqTransportException classifyApiFailure(
            OfficialQqApiException failure) {
        if (OfficialQqEndpoint.TOKEN.category().equals(failure.endpoint())) {
            // Token acquisition completes before the message POST is built/sent. This is the only
            // API failure we can prove happened before the write endpoint was dispatched.
            return OfficialQqTransportException.notDispatched("TOKEN_ACQUISITION_FAILED");
        }
        return OfficialQqTransportException.unknown(errorCode(failure));
    }

    private static String requireExternalId(JsonNode response)
            throws OfficialQqTransportException {
        String id = externalId(response);
        if (id.isEmpty()) {
            throw OfficialQqTransportException.unknown("MALFORMED_RESPONSE");
        }
        return id;
    }

    private static String externalId(JsonNode response) {
        if (response == null) {
            return "";
        }
        for (String path : new String[] {"data.message_id", "message_id", "data.id", "id"}) {
            JsonNode value = response.at("/" + path.replace('.', '/'));
            if (!value.isMissingNode() && !value.isNull()) {
                String id = value.asText("").strip();
                if (!id.isEmpty()) {
                    return id;
                }
            }
        }
        return "";
    }

    private static String errorCode(OfficialQqApiException failure) {
        return switch (failure.kind()) {
            case TIMEOUT -> "TIMEOUT";
            case NETWORK -> "NETWORK";
            case AUTHENTICATION -> "AUTHENTICATION";
            case PERMISSION -> "PERMISSION";
            case NOT_FOUND -> "NOT_FOUND";
            case RATE_LIMITED -> "RATE_LIMITED";
            case SERVER -> "SERVER";
            case MALFORMED_RESPONSE -> "MALFORMED_RESPONSE";
            case HTTP, BUSINESS -> "PROVIDER_NONZERO";
            case CONFIGURATION -> "CONFIGURATION";
        };
    }
}

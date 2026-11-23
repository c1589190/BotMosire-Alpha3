package io.github.c1589190.bma3.qqofficial.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.bma3.qqofficial.media.OfficialQqMediaMessage;
import io.github.c1589190.bma3.qqofficial.media.OfficialQqMediaTransportException;
import io.github.c1589190.bma3.qqofficial.media.OfficialQqMediaTransportPort;
import io.github.c1589190.bma3.qqofficial.media.OfficialQqMediaUpload;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqTransportException;
import java.util.Objects;

/**
 * Fixed-endpoint media adapter over {@link OfficialQqApiClient}.
 *
 * <p>Uploads always set {@code srv_send_msg=false}. The adapter distinguishes explicit upload
 * rejection from a missing/timed-out upload result and never retries. Message sends reuse the
 * existing conservative transport exception boundary.</p>
 */
public final class OfficialQqMediaTransport implements OfficialQqMediaTransportPort {

    private final OfficialQqApiClient api;
    private final String accountId;

    public OfficialQqMediaTransport(OfficialQqApiClient api) {
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
    public OfficialQqMediaUpload uploadGroup(String groupOpenid, int fileType, String fileName,
                                             String base64Data)
            throws OfficialQqMediaTransportException {
        try {
            return uploadResult(api.uploadGroupMedia(
                    groupOpenid, fileType, fileName, base64Data),
                    accountId, OfficialQqMediaUpload.Scene.GROUP, groupOpenid);
        } catch (IllegalArgumentException invalid) {
            throw OfficialQqMediaTransportException.notDispatched("INVALID_REQUEST");
        } catch (OfficialQqApiException failure) {
            throw classifyUploadFailure(failure);
        }
    }

    @Override
    public OfficialQqMediaUpload uploadUser(String userOpenid, int fileType, String fileName,
                                            String base64Data)
            throws OfficialQqMediaTransportException {
        try {
            return uploadResult(api.uploadUserMedia(
                    userOpenid, fileType, fileName, base64Data),
                    accountId, OfficialQqMediaUpload.Scene.PRIVATE, userOpenid);
        } catch (IllegalArgumentException invalid) {
            throw OfficialQqMediaTransportException.notDispatched("INVALID_REQUEST");
        } catch (OfficialQqApiException failure) {
            throw classifyUploadFailure(failure);
        }
    }

    @Override
    public String sendGroupMedia(String groupOpenid, OfficialQqMediaMessage message)
            throws OfficialQqTransportException {
        Objects.requireNonNull(message, "message");
        try {
            return requireExternalId(api.sendGroupMedia(
                    groupOpenid, message.fileInfo(), message.msgId(), message.eventId(),
                    message.msgSeq()));
        } catch (IllegalArgumentException invalid) {
            throw OfficialQqTransportException.notDispatched("INVALID_REQUEST");
        } catch (OfficialQqApiException failure) {
            throw classifySendFailure(failure);
        }
    }

    @Override
    public String sendUserMedia(String userOpenid, OfficialQqMediaMessage message)
            throws OfficialQqTransportException {
        Objects.requireNonNull(message, "message");
        try {
            return requireExternalId(api.sendUserMedia(
                    userOpenid, message.fileInfo(), message.msgId(), message.eventId(),
                    message.msgSeq()));
        } catch (IllegalArgumentException invalid) {
            throw OfficialQqTransportException.notDispatched("INVALID_REQUEST");
        } catch (OfficialQqApiException failure) {
            throw classifySendFailure(failure);
        }
    }

    private static OfficialQqMediaUpload uploadResult(JsonNode response, String accountId,
                                                        OfficialQqMediaUpload.Scene scene,
                                                        String targetOpenid)
            throws OfficialQqMediaTransportException {
        JsonNode fileInfo = field(response, "file_info");
        JsonNode ttl = field(response, "ttl");
        if (fileInfo == null || fileInfo.isMissingNode() || fileInfo.isNull()
                || !fileInfo.isTextual()
                || ttl == null || ttl.isMissingNode() || ttl.isNull()
                || !ttl.isIntegralNumber()) {
            throw OfficialQqMediaTransportException.unknown("UPLOAD_RESPONSE_INVALID");
        }
        try {
            return new OfficialQqMediaUpload(accountId, scene, targetOpenid,
                    fileInfo.asText(), ttl.asLong());
        } catch (IllegalArgumentException invalid) {
            throw OfficialQqMediaTransportException.unknown("UPLOAD_RESPONSE_INVALID");
        }
    }

    private static JsonNode field(JsonNode response, String name) {
        if (response == null) {
            return null;
        }
        JsonNode direct = response.get(name);
        if (direct != null && !direct.isMissingNode() && !direct.isNull()) {
            return direct;
        }
        JsonNode data = response.get("data");
        return data == null ? null : data.get(name);
    }

    private static OfficialQqMediaTransportException classifyUploadFailure(
            OfficialQqApiException failure) {
        if (OfficialQqEndpoint.TOKEN.category().equals(failure.endpoint())) {
            return OfficialQqMediaTransportException.notDispatched(
                    "TOKEN_ACQUISITION_FAILED");
        }
        return switch (failure.kind()) {
            case TIMEOUT -> OfficialQqMediaTransportException.unknown("UPLOAD_TIMEOUT");
            case NETWORK -> OfficialQqMediaTransportException.unknown("UPLOAD_NETWORK");
            case SERVER -> OfficialQqMediaTransportException.unknown("UPLOAD_SERVER");
            case MALFORMED_RESPONSE ->
                    OfficialQqMediaTransportException.unknown("UPLOAD_RESPONSE_INVALID");
            case CONFIGURATION ->
                    OfficialQqMediaTransportException.notDispatched("UPLOAD_CONFIGURATION_FAILED");
            case AUTHENTICATION, PERMISSION, NOT_FOUND, RATE_LIMITED, HTTP, BUSINESS ->
                    OfficialQqMediaTransportException.rejected("UPLOAD_REJECTED");
        };
    }

    private static OfficialQqTransportException classifySendFailure(
            OfficialQqApiException failure) {
        if (OfficialQqEndpoint.TOKEN.category().equals(failure.endpoint())) {
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

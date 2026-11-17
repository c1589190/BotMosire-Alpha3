package io.mosire.bma3.qqofficial.readtools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.bma3.qqofficial.api.OfficialQqApiClient;
import io.mosire.bma3.qqofficial.api.OfficialQqApiException;
import java.util.Objects;

/**
 * Account-scoped official read API seam.
 *
 * <p>Implementations are expected to be read-only and to throw
 * {@link OfficialQqApiException} for permission, not-found, business and transport failures. The
 * provider chooses the account; this interface cannot switch accounts.</p>
 */
public interface OfficialQqReadApi {

    JsonNode groupInfo(String groupOpenid) throws OfficialQqApiException;

    JsonNode groupBotState(String groupOpenid) throws OfficialQqApiException;

    JsonNode userInfo(String userOpenid) throws OfficialQqApiException;

    static OfficialQqReadApi fromClient(OfficialQqApiClient client) {
        Objects.requireNonNull(client, "client");
        return new OfficialQqReadApi() {
            @Override
            public JsonNode groupInfo(String groupOpenid) throws OfficialQqApiException {
                return client.groupInfo(groupOpenid);
            }

            @Override
            public JsonNode groupBotState(String groupOpenid) throws OfficialQqApiException {
                return client.groupBotState(groupOpenid);
            }

            @Override
            public JsonNode userInfo(String userOpenid) throws OfficialQqApiException {
                return client.userInfo(userOpenid);
            }
        };
    }
}

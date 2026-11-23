package io.github.c1589190.bma3.qqofficial.send;

/**
 * Provider-side boundary used by the send service.
 *
 * <p>Implementations must call the fixed group/user send endpoints and must not retry a write
 * request. Any exception is treated by the service as an ambiguous outcome ({@code UNKNOWN}).</p>
 */
public interface OfficialQqSendTransport {

    /** Exact account this transport is bound to; checked before any board row or dispatch. */
    String accountId();

    String sendGroup(String groupOpenid, OfficialQqSendMessage message) throws Exception;

    String sendUser(String userOpenid, OfficialQqSendMessage message) throws Exception;
}

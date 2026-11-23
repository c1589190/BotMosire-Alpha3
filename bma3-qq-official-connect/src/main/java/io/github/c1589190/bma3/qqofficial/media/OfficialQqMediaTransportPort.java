package io.github.c1589190.bma3.qqofficial.media;

import io.github.c1589190.bma3.qqofficial.send.OfficialQqTransportException;

/**
 * Provider boundary for the two fixed official media stages.
 *
 * <p>Upload calls must use the fixed group/user {@code /files} endpoints with
 * {@code srv_send_msg=false}. Message calls must use the fixed group/user {@code /messages}
 * endpoints with {@code msg_type=7}. Implementations must never retry a write request.</p>
 */
public interface OfficialQqMediaTransportPort {

    /** Exact account this transport is bound to; checked before any board row or dispatch. */
    String accountId();

    OfficialQqMediaUpload uploadGroup(String groupOpenid, int fileType, String fileName,
                                      String base64Data)
            throws OfficialQqMediaTransportException;

    OfficialQqMediaUpload uploadUser(String userOpenid, int fileType, String fileName,
                                     String base64Data)
            throws OfficialQqMediaTransportException;

    String sendGroupMedia(String groupOpenid, OfficialQqMediaMessage message)
            throws OfficialQqTransportException;

    String sendUserMedia(String userOpenid, OfficialQqMediaMessage message)
            throws OfficialQqTransportException;
}

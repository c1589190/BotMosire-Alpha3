package io.github.c1589190.bma3.qqofficial.media;

import java.util.Objects;

/** One official rich-media message, always sent as {@code msg_type=7}. */
public record OfficialQqMediaMessage(String fileInfo, String msgId, String eventId, Integer msgSeq) {

    public OfficialQqMediaMessage {
        Objects.requireNonNull(fileInfo, "fileInfo");
        if (fileInfo.isBlank()) {
            throw new IllegalArgumentException("file_info 不能为空");
        }
        msgId = msgId == null ? "" : msgId.strip();
        eventId = eventId == null ? "" : eventId.strip();
        if (!msgId.isEmpty() && !eventId.isEmpty()) {
            throw new IllegalArgumentException("msg_id 与 event_id 只能二选一");
        }
        if (msgSeq != null && msgId.isEmpty()) {
            throw new IllegalArgumentException("msg_seq 只能与 msg_id 一起使用");
        }
        if (msgSeq != null && msgSeq < 1) {
            throw new IllegalArgumentException("msg_seq 必须为正整数");
        }
    }

    public boolean passive() {
        return !msgId.isEmpty() || !eventId.isEmpty();
    }

    @Override
    public String toString() {
        return "OfficialQqMediaMessage[msgType=7, passive=" + passive()
                + ", msgSeq=" + msgSeq + "]";
    }
}

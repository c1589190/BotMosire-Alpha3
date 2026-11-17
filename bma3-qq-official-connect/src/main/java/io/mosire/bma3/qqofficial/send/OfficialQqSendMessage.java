package io.mosire.bma3.qqofficial.send;

/**
 * One official text message request. {@code msgId}/{@code eventId} are opaque strings and are
 * mutually exclusive; {@code msgSeq} is meaningful only with a passive {@code msgId}.
 */
public record OfficialQqSendMessage(String content, String msgId, String eventId, Integer msgSeq) {

    public OfficialQqSendMessage {
        content = content == null ? "" : content;
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
        return "OfficialQqSendMessage[msgType=text, contentLength="
                + content.codePointCount(0, content.length())
                + ", passive=" + passive()
                + ", msgSeq=" + msgSeq + "]";
    }
}

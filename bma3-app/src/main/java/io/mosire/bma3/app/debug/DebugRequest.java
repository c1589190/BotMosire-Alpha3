package io.mosire.bma3.app.debug;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * One remote question and, once the Agent answers, its reply.
 *
 * <p>Only this file carries question/answer text. Audit, logs and the HTTP status endpoint never
 * echo bodies (red line: 正文不落日志).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DebugRequest(
        String id,
        String question,
        Status status,
        Instant createdAt,
        Instant expiresAt,
        Instant answeredAt,
        String answer,
        String injectedSource) {

    public enum Status {
        PENDING, ANSWERED, EXPIRED
    }

    public DebugRequest {
        id = id == null ? "" : id;
        question = question == null ? "" : question;
        status = status == null ? Status.PENDING : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        expiresAt = expiresAt == null ? createdAt : expiresAt;
        answer = answer == null ? "" : answer;
        injectedSource = injectedSource == null ? "" : injectedSource;
    }

    /** True when the deadline passed while still unanswered. */
    public boolean timedOut(Instant now) {
        return status == Status.PENDING && !now.isBefore(expiresAt);
    }

    public DebugRequest answered(String text, Instant at) {
        return new DebugRequest(id, question, Status.ANSWERED, createdAt, expiresAt, at, text,
                injectedSource);
    }

    public DebugRequest expired(Instant at) {
        return new DebugRequest(id, question, Status.EXPIRED, createdAt, expiresAt, at, "",
                injectedSource);
    }

    /** Answer text is bounded by the caller; this is only the projection used over HTTP. */
    public DebugRequest redactedQuestion(int maxChars) {
        String q = question.length() <= maxChars ? question : question.substring(0, maxChars) + "…";
        return new DebugRequest(id, q, status, createdAt, expiresAt, answeredAt, answer,
                injectedSource);
    }
}

package io.mosire.bma3.app.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.app.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remote ask/quarantine surface: turns an HTTP question into one Inbox item for the bound DSH
 * session and hands the Agent's reply back to the caller.
 *
 * <p>Design contract (spec {@code 2026-11-15-debug-channel-schedule-triggers-design.md} §2):</p>
 *
 * <ul>
 *   <li>questions travel through the Inbox only — there is no second delivery channel;</li>
 *   <li>state is {@code PENDING → ANSWERED | EXPIRED}, one-way, and survives a restart;</li>
 *   <li>no single-flight (user ruling); the only concurrency guard is the configurable
 *       {@code debug.maxPending} cap;</li>
 *   <li>bodies stay in {@code requests.json} (0600); audit lines carry sizes, never text.</li>
 * </ul>
 */
public final class DebugService {

    private static final Logger LOG = LoggerFactory.getLogger(DebugService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DebugStore store;
    private final Supplier<AppConfig.Debug> options;
    private final Clock clock;
    private final Function<DebugRequest, Boolean> sink;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object monitor = new Object();
    private final LinkedHashMap<String, DebugRequest> requests = new LinkedHashMap<>();

    public DebugService(DebugStore store, Supplier<AppConfig.Debug> options,
                        Function<DebugRequest, Boolean> sink) {
        this(store, options, Clock.systemUTC(), sink);
    }

    public DebugService(DebugStore store, Supplier<AppConfig.Debug> options, Clock clock,
                        Function<DebugRequest, Boolean> sink) {
        this.store = Objects.requireNonNull(store, "store");
        this.options = options == null ? AppConfig.Debug::defaults : options;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.sink = sink == null ? request -> false : sink;
        for (DebugRequest loaded : store.load()) {
            if (loaded != null && !loaded.id().isBlank()) {
                requests.put(loaded.id(), loaded);
            }
        }
    }

    /**
     * Ask the bound session one question.
     *
     * @param question       free text, bounded by {@code debug.questionMaxBytes}
     * @param timeoutSeconds per-request deadline, clamped to 5..600, default from config
     * @param wait           when true, block until answered or the deadline passes
     * @param peer           caller address for the audit line (no body)
     */
    public DebugRequest ask(String question, Integer timeoutSeconds, boolean wait, String peer) {
        AppConfig.Debug limits = options.get();
        String text = question == null ? "" : question.strip();
        if (text.isEmpty()) {
            throw new DebugException(400, "question_required", "question 不能为空");
        }
        int questionBytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (questionBytes > limits.questionMaxBytes()) {
            throw new DebugException(413, "question_too_large",
                    "question 超过 " + limits.questionMaxBytes() + " 字节");
        }
        int timeout = timeoutSeconds == null ? limits.askTimeoutSeconds()
                : Math.max(5, Math.min(600, timeoutSeconds));

        DebugRequest created;
        synchronized (monitor) {
            sweepLocked();
            if (limits.maxPending() > 0 && pendingLocked() >= limits.maxPending()) {
                throw new DebugException(429, "too_many_pending",
                        "已有 " + pendingLocked() + " 个未答提问（debug.maxPending="
                                + limits.maxPending() + "）");
            }
            Instant now = clock.instant();
            String id = "rq-" + hex(6);
            created = new DebugRequest(id, text, DebugRequest.Status.PENDING, now,
                    now.plusSeconds(timeout), null, "", "bma3_debug/" + id);
            requests.put(id, created);
            persistLocked();
        }

        boolean injected;
        try {
            injected = sink.apply(created);
        } catch (RuntimeException failure) {
            LOG.warn("event=debug_ask_inject_failed requestId={} error={}", created.id(),
                    failure.getClass().getSimpleName());
            injected = false;
        }
        if (!injected) {
            synchronized (monitor) {
                requests.put(created.id(), created.expired(clock.instant()));
                persistLocked();
            }
            audit(created.id(), "inject_failed", questionBytes, 0, peer);
            throw new DebugException(503, "inject_failed",
                    "问题未能写入 Inbox，未进入会话；请查 watchdog 快照里的 inboxHttp/inboxBacklog");
        }
        audit(created.id(), "asked", questionBytes, 0, peer);

        if (!wait) {
            return created;
        }
        return await(created.id(), timeout * 1000L);
    }

    /**
     * Panel text for one question. Kept here (not in the composition root) so the exact wording the
     * Agent needs in order to answer is unit-testable.
     */
    public static String inboxSummary(DebugRequest request) {
        return "【调试提问 " + request.id() + "】\n" + request.question()
                + "\n→ 用 bma3_debug_reply(requestId=\"" + request.id() + "\") 回答；"
                + "回答后本条可正常 ack。";
    }

    /** Poll-free blocking wait: the answer() path notifies the monitor. */
    private DebugRequest await(String id, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (monitor) {
            while (true) {
                DebugRequest current = requests.get(id);
                if (current == null) {
                    throw new DebugException(404, "unknown_request", "请求不存在");
                }
                if (current.status() != DebugRequest.Status.PENDING) {
                    return current;
                }
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    sweepLocked();
                    return requests.getOrDefault(id, current);
                }
                try {
                    monitor.wait(Math.min(leftMillis, 500));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return requests.getOrDefault(id, current);
                }
                sweepLocked();
            }
        }
    }

    /** Record the Agent's reply. Idempotency is one-way: the first answer wins. */
    public DebugRequest answer(String requestId, String text, String peer) {
        AppConfig.Debug limits = options.get();
        String body = text == null ? "" : text;
        if (body.isBlank()) {
            throw new DebugException(400, "answer_required", "text 不能为空");
        }
        int answerBytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (answerBytes > limits.answerMaxBytes()) {
            throw new DebugException(413, "answer_too_large",
                    "text 超过 " + limits.answerMaxBytes() + " 字节");
        }
        DebugRequest answered;
        synchronized (monitor) {
            sweepLocked();
            DebugRequest current = requests.get(requestId == null ? "" : requestId);
            if (current == null) {
                throw new DebugException(404, "unknown_request", "请求不存在: " + requestId);
            }
            if (current.status() == DebugRequest.Status.ANSWERED) {
                throw new DebugException(409, "already_answered", "该请求已回答，首个回答生效");
            }
            if (current.status() == DebugRequest.Status.EXPIRED) {
                throw new DebugException(409, "request_expired", "该请求已过期，不再接收回答");
            }
            answered = current.answered(body, clock.instant());
            requests.put(answered.id(), answered);
            persistLocked();
            monitor.notifyAll();
        }
        audit(answered.id(), "answered", answered.question()
                .getBytes(StandardCharsets.UTF_8).length, answerBytes, peer);
        return answered;
    }

    public DebugRequest get(String requestId) {
        synchronized (monitor) {
            sweepLocked();
            DebugRequest current = requests.get(requestId == null ? "" : requestId);
            if (current == null) {
                throw new DebugException(404, "unknown_request", "请求不存在: " + requestId);
            }
            return current;
        }
    }

    public List<DebugRequest> list(DebugRequest.Status status, int limit) {
        int capped = Math.max(1, Math.min(200, limit));
        synchronized (monitor) {
            sweepLocked();
            List<DebugRequest> values = new ArrayList<>(requests.values());
            values.sort(Comparator.comparing(DebugRequest::createdAt).reversed());
            List<DebugRequest> filtered = new ArrayList<>();
            for (DebugRequest request : values) {
                if (status == null || request.status() == status) {
                    filtered.add(request);
                    if (filtered.size() >= capped) {
                        break;
                    }
                }
            }
            return filtered;
        }
    }

    public int pendingCount() {
        synchronized (monitor) {
            sweepLocked();
            return pendingLocked();
        }
    }

    /** Expire overdue requests; cheap and called on every read so no timer thread is needed. */
    public void sweep() {
        synchronized (monitor) {
            sweepLocked();
        }
    }

    /** Where bodies live, so callers can log the path without ever logging text. */
    public String storePath() {
        return store.file().toString();
    }

    private void sweepLocked() {
        Instant now = clock.instant();
        boolean changed = false;
        for (Map.Entry<String, DebugRequest> entry : new ArrayList<>(requests.entrySet())) {
            DebugRequest current = entry.getValue();
            if (current.timedOut(now)) {
                requests.put(entry.getKey(), current.expired(now));
                changed = true;
                audit(entry.getKey(), "expired", 0, 0, "");
            }
        }
        if (changed) {
            persistLocked();
        }
    }

    private int pendingLocked() {
        int pending = 0;
        for (DebugRequest request : requests.values()) {
            if (request.status() == DebugRequest.Status.PENDING) {
                pending++;
            }
        }
        return pending;
    }

    /** Keep every pending request plus the newest decided ones, bounded by retention. */
    private void persistLocked() {
        int retention = Math.max(10, options.get().retention());
        List<DebugRequest> all = new ArrayList<>(requests.values());
        if (all.size() > retention) {
            List<DebugRequest> keep = new ArrayList<>();
            List<DebugRequest> decided = new ArrayList<>();
            for (DebugRequest request : all) {
                if (request.status() == DebugRequest.Status.PENDING) {
                    keep.add(request);
                } else {
                    decided.add(request);
                }
            }
            decided.sort(Comparator.comparing(DebugRequest::createdAt).reversed());
            int room = Math.max(0, retention - keep.size());
            keep.addAll(decided.subList(0, Math.min(room, decided.size())));
            requests.clear();
            for (DebugRequest request : all) {
                if (keep.contains(request)) {
                    requests.put(request.id(), request);
                }
            }
        }
        store.save(new ArrayList<>(requests.values()));
    }

    private void audit(String requestId, String event, int questionBytes, int answerBytes,
                       String peer) {
        try {
            store.audit(mapper.writeValueAsString(Map.of(
                    "at", clock.instant().toString(),
                    "requestId", requestId,
                    "event", event,
                    "questionBytes", questionBytes,
                    "answerBytes", answerBytes,
                    "peer", peer == null ? "" : peer)));
        } catch (Exception failure) {
            LOG.warn("event=debug_audit_serialize_failed requestId={}", requestId);
        }
    }

    public Optional<DebugRequest> find(String requestId) {
        synchronized (monitor) {
            return Optional.ofNullable(requests.get(requestId == null ? "" : requestId));
        }
    }

    private static String hex(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        StringBuilder out = new StringBuilder(bytes * 2);
        for (byte value : raw) {
            out.append(String.format(java.util.Locale.ROOT, "%02x", value));
        }
        return out.toString();
    }
}

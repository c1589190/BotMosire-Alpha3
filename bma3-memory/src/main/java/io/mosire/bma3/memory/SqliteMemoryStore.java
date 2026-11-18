package io.mosire.bma3.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * SQLite notebook: {@code memory_item} plus an external-content {@code memory_fts} index.
 *
 * <p>The FTS index uses {@code tokenize='trigram'} because the corpus is Chinese and the SQLite
 * build in this project has no ICU tokenizer: trigram indexes any substring of three or more
 * characters. It does <em>not</em> index shorter substrings, so two-character Chinese tags are
 * matched on {@code tags_norm} instead of through FTS — that is the whole reason the tag leg
 * exists as a separate ranked list.</p>
 *
 * <p>Rows are never deleted or updated in place except for {@code weight}, {@code last_used_at}
 * and {@code use_count}.</p>
 */
public final class SqliteMemoryStore implements MemoryStore {

    private static final int RRF_RANK_CONSTANT = 60;
    private static final int DIGEST_CHARS = 60;
    private static final int MIN_TRIGRAM_CHARS = 3;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("SQLite JDBC 驱动缺失", failure);
        }
    }

    private final Connection connection;
    private final Supplier<MemoryOptions> options;
    private final UnaryOperator<String> sanitizer;
    private boolean closed;

    public SqliteMemoryStore(Path databaseFile, Supplier<MemoryOptions> options,
            UnaryOperator<String> sanitizer) {
        this.options = Objects.requireNonNull(options, "options");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        Objects.requireNonNull(databaseFile, "databaseFile");
        Path absolute = databaseFile.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS memory_item (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          text TEXT NOT NULL,
                          tags TEXT NOT NULL DEFAULT '',
                          tags_norm TEXT NOT NULL DEFAULT '',
                          weight REAL NOT NULL DEFAULT 0,
                          source_kind TEXT NOT NULL,
                          source_ref TEXT,
                          context_ref TEXT,
                          created_at TEXT NOT NULL,
                          last_used_at TEXT,
                          use_count INTEGER NOT NULL DEFAULT 0
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_memory_item_weight
                        ON memory_item(weight, created_at DESC)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_memory_item_tags
                        ON memory_item(tags_norm)
                        """);
                statement.execute("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts
                        USING fts5(text, content='memory_item', content_rowid='id',
                                   tokenize='trigram')
                        """);
                // Append-only: an insert trigger is the only synchronisation the index needs.
                statement.execute("""
                        CREATE TRIGGER IF NOT EXISTS memory_item_ai
                        AFTER INSERT ON memory_item BEGIN
                          INSERT INTO memory_fts(rowid, text) VALUES (new.id, new.text);
                        END
                        """);
            }
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("打开 SQLite 记忆库失败", failure);
        }
    }

    @Override
    public synchronized long write(String text, String tags, String sourceKind, String sourceRef,
            String contextRef, Instant now) {
        ensureOpen();
        MemoryOptions settings = options.get();
        String body = sanitize(Objects.requireNonNull(text, "text"));
        if (body.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        if (body.length() > settings.maxTextChars()) {
            throw new IllegalArgumentException("text 超过上限 " + settings.maxTextChars() + " 字符");
        }
        List<String> terms = TagTerms.parse(tags);
        String human = String.join(" ", terms);
        String normalized = TagTerms.normalize(terms);
        String kind = sourceKind == null || sourceKind.isBlank() ? "agent" : sourceKind.trim();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO memory_item(text, tags, tags_norm, weight, source_kind, source_ref,
                                        context_ref, created_at, last_used_at, use_count)
                VALUES(?,?,?,?,?,?,?,?,NULL,0)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, body);
            insert.setString(2, human);
            insert.setString(3, normalized);
            insert.setDouble(4, 0.0);
            insert.setString(5, kind);
            insert.setString(6, blankToNull(sourceRef));
            insert.setString(7, blankToNull(contextRef));
            insert.setString(8, instant(now));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("memory_item 未返回主键");
                }
                return keys.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("写入记忆失败", failure);
        }
    }

    @Override
    public synchronized List<MemoryHit> search(String query, int limit, int offset) {
        ensureOpen();
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit 必须在 1..50");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负");
        }
        String raw = query == null ? "" : query.trim();
        int pool = Math.max((limit + offset) * 5, 50);
        MemoryOptions settings = options.get();
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, Integer> tagRanks = tagRanks(raw, pool);
        Map<Long, Integer> textRanks = textRanks(raw, pool);
        for (Map.Entry<Long, Integer> hit : tagRanks.entrySet()) {
            scores.merge(hit.getKey(),
                    settings.tagBoost() / (RRF_RANK_CONSTANT + hit.getValue()), Double::sum);
        }
        for (Map.Entry<Long, Integer> hit : textRanks.entrySet()) {
            scores.merge(hit.getKey(),
                    1.0 / (RRF_RANK_CONSTANT + hit.getValue()), Double::sum);
        }
        if (scores.isEmpty()) {
            return List.of();
        }
        Map<Long, Row> rows = load(scores.keySet());
        List<Map.Entry<Long, Double>> ordered = new ArrayList<>(scores.entrySet());
        ordered.removeIf(entry -> {
            Row row = rows.get(entry.getKey());
            return row == null || row.weight() == MemoryOptions.MUTED;
        });
        ordered.sort(Comparator
                .<Map.Entry<Long, Double>>comparingDouble(Map.Entry::getValue).reversed()
                .thenComparing(entry -> rows.get(entry.getKey()).createdAt(),
                        Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()));
        List<MemoryHit> hits = new ArrayList<>();
        int index = 0;
        for (Map.Entry<Long, Double> entry : ordered) {
            if (index++ < offset) {
                continue;
            }
            Row row = rows.get(entry.getKey());
            hits.add(new MemoryHit(row.id(), digest(row), row.tags(), row.weight(),
                    row.createdAt()));
            if (hits.size() >= limit) {
                break;
            }
        }
        return hits;
    }

    @Override
    public synchronized Optional<MemoryItem> view(long id, Instant now, boolean markUsed) {
        ensureOpen();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id, text, tags, weight, source_kind, source_ref, context_ref, created_at,
                       last_used_at, use_count
                FROM memory_item WHERE id = ?
                """)) {
            select.setLong(1, id);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                MemoryItem item = item(rows);
                if (markUsed) {
                    try (PreparedStatement touch = connection.prepareStatement("""
                            UPDATE memory_item SET use_count = use_count + 1, last_used_at = ?
                            WHERE id = ?
                            """)) {
                        touch.setString(1, instant(now));
                        touch.setLong(2, id);
                        touch.executeUpdate();
                    }
                    item = new MemoryItem(item.id(), item.text(), item.tags(), item.weight(),
                            item.sourceKind(), item.sourceRef(), item.contextRef(),
                            item.createdAt(), now, item.useCount() + 1);
                }
                return Optional.of(item);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取记忆失败", failure);
        }
    }

    @Override
    public synchronized double rate(long id, Vote vote, Instant now) {
        ensureOpen();
        MemoryOptions settings = options.get();
        MemoryItem current = view(id, now, false)
                .orElseThrow(() -> new IllegalArgumentException("记忆不存在: " + id));
        double next = switch (vote) {
            case MUTE -> MemoryOptions.MUTED;
            case USEFUL -> {
                requireNotMuted(current);
                yield Math.max(settings.floor(),
                        current.weight() + settings.usefulDelta());
            }
            case USELESS -> {
                requireNotMuted(current);
                yield Math.max(settings.floor(),
                        current.weight() + settings.uselessDelta());
            }
        };
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE memory_item SET weight = ? WHERE id = ?")) {
            update.setDouble(1, next);
            update.setLong(2, id);
            update.executeUpdate();
            return next;
        } catch (SQLException failure) {
            throw new IllegalStateException("更新记忆权重失败", failure);
        }
    }

    private static void requireNotMuted(MemoryItem item) {
        if (item.muted()) {
            throw new IllegalStateException(
                    "记忆 " + item.id() + " 已静音（weight=" + MemoryOptions.MUTED
                            + "），静音不可逆");
        }
    }

    /** Tag leg: exact {@code |term|} containment, ordered by matched-term count. */
    private Map<Long, Integer> tagRanks(String query, int pool) {
        List<String> terms = TagTerms.parse(query);
        if (terms.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, tags_norm, created_at FROM memory_item WHERE weight <> ? AND (");
        for (int i = 0; i < terms.size(); i++) {
            sql.append(i == 0 ? "" : " OR ").append("tags_norm LIKE ?");
        }
        sql.append(") ORDER BY created_at DESC, id DESC LIMIT ?");
        try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
            int index = 1;
            select.setDouble(index++, MemoryOptions.MUTED);
            for (String term : terms) {
                select.setString(index++, "%" + TagTerms.delimiter() + term
                        + TagTerms.delimiter() + "%");
            }
            select.setInt(index, pool);
            List<Map.Entry<Long, Integer>> ranked = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    String normalized = rows.getString("tags_norm");
                    int matched = 0;
                    for (String term : terms) {
                        if (normalized.contains(TagTerms.delimiter() + term
                                + TagTerms.delimiter())) {
                            matched++;
                        }
                    }
                    if (matched > 0) {
                        ranked.add(Map.entry(rows.getLong("id"), matched));
                    }
                }
            }
            ranked.sort(Comparator
                    .<Map.Entry<Long, Integer>>comparingInt(Map.Entry::getValue).reversed()
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()));
            Map<Long, Integer> ranks = new LinkedHashMap<>();
            int rank = 1;
            for (Map.Entry<Long, Integer> entry : ranked) {
                ranks.put(entry.getKey(), rank++);
            }
            return ranks;
        } catch (SQLException failure) {
            throw new IllegalStateException("标签检索失败", failure);
        }
    }

    /** Text leg: BM25 over the trigram index. Queries shorter than one trigram skip this leg. */
    /**
     * Body leg. Trigram FTS needs at least {@link #MIN_TRIGRAM_CHARS} code points, which rules out
     * the two-character Chinese terms this notebook is mostly searched with ("例会", "站会"), so
     * anything shorter falls back to a literal substring scan. Both legs rank by relevance first
     * and feed the same RRF merge, so callers never see which one answered.
     */
    private Map<Long, Integer> textRanks(String query, int pool) {
        String phrase = query.replace("\"", " ").trim();
        if (phrase.codePointCount(0, phrase.length()) < MIN_TRIGRAM_CHARS) {
            return likeRanks(phrase, pool);
        }
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT m.id AS id
                FROM memory_fts
                JOIN memory_item m ON m.id = memory_fts.rowid
                WHERE memory_fts MATCH ? AND m.weight <> ?
                ORDER BY bm25(memory_fts) LIMIT ?
                """)) {
            select.setString(1, "\"" + phrase + "\"");
            select.setDouble(2, MemoryOptions.MUTED);
            select.setInt(3, pool);
            Map<Long, Integer> ranks = new LinkedHashMap<>();
            int rank = 1;
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    ranks.put(rows.getLong("id"), rank++);
                }
            }
            return ranks;
        } catch (SQLException failure) {
            throw new IllegalStateException("正文检索失败", failure);
        }
    }

    private Map<Long, Integer> likeRanks(String phrase, int pool) {
        if (phrase.isEmpty()) {
            return Map.of();
        }
        String escaped = phrase.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id FROM memory_item
                WHERE weight <> ? AND text LIKE ? ESCAPE '\\'
                ORDER BY created_at DESC, id DESC LIMIT ?
                """)) {
            select.setDouble(1, MemoryOptions.MUTED);
            select.setString(2, "%" + escaped + "%");
            select.setInt(3, pool);
            Map<Long, Integer> ranks = new LinkedHashMap<>();
            int rank = 1;
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    ranks.put(rows.getLong("id"), rank++);
                }
            }
            return ranks;
        } catch (SQLException failure) {
            throw new IllegalStateException("正文子串检索失败", failure);
        }
    }

    private Map<Long, Row> load(java.util.Collection<Long> ids) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id, text, tags, weight, source_kind, created_at
                FROM memory_item WHERE id IN (%s)
                """.formatted(placeholders))) {
            int index = 1;
            for (Long id : ids) {
                select.setLong(index++, id);
            }
            Map<Long, Row> rows = new LinkedHashMap<>();
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    rows.put(result.getLong("id"), new Row(result.getLong("id"),
                            result.getString("text"), result.getString("tags"),
                            result.getDouble("weight"), result.getString("source_kind"),
                            instantOf(result.getString("created_at"))));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw new IllegalStateException("读取记忆摘要失败", failure);
        }
    }

    private String digest(Row row) {
        String flat = row.text().replaceAll("\\s+", " ").strip();
        String head = flat.length() <= DIGEST_CHARS ? flat : flat.substring(0, DIGEST_CHARS) + "…";
        String tagPart = row.tags().isBlank() ? "-" : "[" + row.tags().replace(' ', '|') + "]";
        return "#" + row.id() + " · " + tagPart + " · " + row.createdAt() + " · " + head;
    }

    private String sanitize(String text) {
        return sanitizer.apply(text).strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String instant(Instant now) {
        return (now == null ? Instant.now() : now).toString();
    }

    private static Instant instantOf(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            return Instant.EPOCH;
        }
    }

    private static MemoryItem item(ResultSet rows) throws SQLException {
        String lastUsed = rows.getString("last_used_at");
        return new MemoryItem(rows.getLong("id"), rows.getString("text"), rows.getString("tags"),
                rows.getDouble("weight"), rows.getString("source_kind"),
                rows.getString("source_ref"), rows.getString("context_ref"),
                instantOf(rows.getString("created_at")),
                lastUsed == null ? null : instantOf(lastUsed), rows.getLong("use_count"));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("记忆库已关闭");
        }
    }

    @Override
    public synchronized boolean ping() {
        if (closed) {
            return false;
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT 1")) {
            return rows.next();
        } catch (SQLException failure) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("关闭记忆库失败", failure);
        }
    }

    private record Row(long id, String text, String tags, double weight, String sourceKind,
            Instant createdAt) {
    }

    /** Tag parsing shared by the writer and both search legs. */
    static final class TagTerms {

        private static final String DELIMITER = "|";

        private TagTerms() {
        }

        static String delimiter() {
            return DELIMITER;
        }

        static List<String> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            for (String piece : raw.split("[\\s,，、;；]+")) {
                String term = piece.replace(DELIMITER, "").strip();
                if (!term.isEmpty()) {
                    terms.add(term.toLowerCase(Locale.ROOT));
                }
            }
            return List.copyOf(terms);
        }

        static String normalize(List<String> terms) {
            if (terms.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder(DELIMITER);
            for (String term : terms) {
                builder.append(term).append(DELIMITER);
            }
            return builder.toString();
        }
    }
}

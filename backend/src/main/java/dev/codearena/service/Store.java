package dev.codearena.service;

import dev.codearena.service.Judge.TestCase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** All SQL in one place. */
@Repository
public class Store {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    public record User(long id, String username) {}

    public record ProblemRow(long id, String slug, String title, String difficulty, String functionName, List<String> argNames, String statement, List<String> topics) {}

    public record ProblemSummary(String slug, String title, String difficulty, List<String> topics, boolean solved, boolean attempted) {}

    public record SubmissionRow(long id, String problemSlug, String problemTitle, String language, String status, int passed, int total, String message, long runtimeMs, Instant createdAt) {}

    public record LeaderRow(int rank, String username, int solved, int points) {}

    private final JdbcClient jdbc;

    public Store(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- users and sessions ----

    public Optional<User> userById(long id) {
        return jdbc.sql("SELECT id, username FROM app_user WHERE id = :id").param("id", id).query((r, n) -> new User(r.getLong(1), r.getString(2))).optional();
    }

    public Optional<Object[]> credentials(String username) {
        return jdbc.sql("SELECT id, password_hash FROM app_user WHERE username = :u").param("u", username).query((r, n) -> new Object[] {r.getLong(1), r.getString(2)}).optional();
    }

    /** Returns the new user id, or empty if the name is taken. */
    public Optional<Long> insertUser(String username, String hash) {
        return jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES (:u, :h) ON CONFLICT (username) DO NOTHING RETURNING id")
                .param("u", username).param("h", hash).query(Long.class).optional();
    }

    public String createSession(long userId, int days) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        jdbc.sql("INSERT INTO session (token_hash, user_id, expires_at) VALUES (:t, :u, :e)")
                .param("t", hash(token)).param("u", userId).param("e", Timestamp.from(Instant.now().plusSeconds(days * 86400L))).update();
        return token;
    }

    public Optional<User> userForToken(String token) {
        if (token == null || token.length() > 100) return Optional.empty();
        return jdbc.sql("SELECT u.id, u.username FROM session s JOIN app_user u ON u.id = s.user_id WHERE s.token_hash = :t AND s.expires_at > now()")
                .param("t", hash(token)).query((r, n) -> new User(r.getLong(1), r.getString(2))).optional();
    }

    public void deleteSession(String token) {
        if (token != null) jdbc.sql("DELETE FROM session WHERE token_hash = :t").param("t", hash(token)).update();
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- problems ----

    public long problemCount() {
        return jdbc.sql("SELECT count(*) FROM problem").query(Long.class).single();
    }

    public boolean hasProblem(String slug) {
        return jdbc.sql("SELECT count(*) FROM problem WHERE slug = :s").param("s", slug).query(Long.class).single() > 0;
    }

    public void insertProblem(JsonNode p) {
        long id = jdbc.sql("INSERT INTO problem (slug, title, difficulty, function_name, arg_names, statement) VALUES (:s,:t,:d,:f,:a,:st) RETURNING id")
                .param("s", p.path("slug").asString()).param("t", p.path("title").asString()).param("d", p.path("difficulty").asString())
                .param("f", p.path("functionName").asString()).param("a", p.path("argNames").toString()).param("st", p.path("statement").asString())
                .query(Long.class).single();
        for (JsonNode t : p.path("topics")) jdbc.sql("INSERT INTO problem_topic VALUES (:p, :t)").param("p", id).param("t", t.asString()).update();
        int ord = 0;
        for (JsonNode t : p.path("tests")) {
            jdbc.sql("INSERT INTO test_case (problem_id, ord, args, expected, hidden) VALUES (:p,:o,:a,:e,:h)")
                    .param("p", id).param("o", ord++).param("a", t.path("args").toString()).param("e", t.path("expected").toString()).param("h", t.path("hidden").asBoolean()).update();
        }
    }

    private Map<Long, List<String>> topicsByProblem() {
        Map<Long, List<String>> m = new LinkedHashMap<>();
        jdbc.sql("SELECT problem_id, topic FROM problem_topic ORDER BY topic").query((r, n) -> {
            m.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(r.getString(2));
            return null;
        }).list();
        return m;
    }

    public List<ProblemSummary> problems(String difficulty, String topic, String q, Long userId) {
        var topics = topicsByProblem();
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.slug, p.title, p.difficulty,
                  EXISTS (SELECT 1 FROM submission s WHERE s.user_id = :u AND s.problem_id = p.id AND s.status = 'ACCEPTED') AS solved,
                  EXISTS (SELECT 1 FROM submission s WHERE s.user_id = :u AND s.problem_id = p.id) AS attempted
                FROM problem p WHERE 1 = 1""");
        if (difficulty != null) sql.append(" AND p.difficulty = :d");
        if (topic != null) sql.append(" AND EXISTS (SELECT 1 FROM problem_topic t WHERE t.problem_id = p.id AND t.topic = :t)");
        if (q != null) sql.append(" AND p.title ILIKE :q");
        sql.append(" ORDER BY CASE p.difficulty WHEN 'easy' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END, p.title");
        var s = jdbc.sql(sql.toString()).param("u", userId == null ? -1L : userId);
        if (difficulty != null) s = s.param("d", difficulty);
        if (topic != null) s = s.param("t", topic);
        if (q != null) s = s.param("q", "%" + q.replaceAll("[\\\\%_]", "\\\\$0") + "%");
        return s.query((r, n) -> new ProblemSummary(r.getString("slug"), r.getString("title"), r.getString("difficulty"), topics.getOrDefault(r.getLong("id"), List.of()), r.getBoolean("solved"), r.getBoolean("attempted"))).list();
    }

    public List<String> allTopics() {
        return jdbc.sql("SELECT DISTINCT topic FROM problem_topic ORDER BY topic").query(String.class).list();
    }

    public Optional<ProblemRow> problem(String slug) {
        return jdbc.sql("SELECT id, slug, title, difficulty, function_name, arg_names, statement FROM problem WHERE slug = :s").param("s", slug).query((r, n) -> {
            List<String> args = new ArrayList<>();
            for (JsonNode a : MAPPER.readTree(r.getString("arg_names"))) args.add(a.asString());
            return new ProblemRow(r.getLong("id"), r.getString("slug"), r.getString("title"), r.getString("difficulty"), r.getString("function_name"), args, r.getString("statement"), topicsByProblem().getOrDefault(r.getLong("id"), List.of()));
        }).optional();
    }

    public List<TestCase> tests(long problemId, boolean visibleOnly) {
        return jdbc.sql("SELECT ord, args, expected, hidden FROM test_case WHERE problem_id = :p" + (visibleOnly ? " AND NOT hidden" : "") + " ORDER BY ord")
                .param("p", problemId).query((r, n) -> new TestCase(r.getInt(1), MAPPER.readTree(r.getString(2)), MAPPER.readTree(r.getString(3)), r.getBoolean(4))).list();
    }

    public boolean solved(long userId, long problemId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM submission WHERE user_id = :u AND problem_id = :p AND status = 'ACCEPTED')").param("u", userId).param("p", problemId).query(Boolean.class).single();
    }

    // ---- submissions ----

    public long insertSubmission(long userId, long problemId, String language, String code, Judge.Verdict v) {
        return jdbc.sql("INSERT INTO submission (user_id, problem_id, language, code, status, passed, total, message, runtime_ms) VALUES (:u,:p,:l,:c,:s,:pa,:t,:m,:r) RETURNING id")
                .param("u", userId).param("p", problemId).param("l", language).param("c", code).param("s", v.status()).param("pa", v.passed()).param("t", v.total())
                .param("m", v.message() == null ? "" : v.message()).param("r", v.runtimeMs()).query(Long.class).single();
    }

    public List<SubmissionRow> submissions(long userId, String problemSlug, int limit) {
        return jdbc.sql("""
                SELECT s.id, p.slug, p.title, s.language, s.status, s.passed, s.total, s.message, s.runtime_ms, s.created_at FROM submission s JOIN problem p ON p.id = s.problem_id
                WHERE s.user_id = :u AND (CAST(:slug AS text) IS NULL OR p.slug = :slug) ORDER BY s.id DESC LIMIT :l""")
                .param("u", userId).param("slug", problemSlug).param("l", limit).query((r, n) -> row(r)).list();
    }

    public Optional<Map<String, Object>> submissionWithCode(long userId, long id) {
        return jdbc.sql("SELECT s.id, s.code, s.language, s.status, s.passed, s.total, s.message, p.slug FROM submission s JOIN problem p ON p.id = s.problem_id WHERE s.id = :i AND s.user_id = :u")
                .param("i", id).param("u", userId).query((r, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getLong(1)); m.put("code", r.getString(2)); m.put("language", r.getString(3)); m.put("status", r.getString(4));
                    m.put("passed", r.getInt(5)); m.put("total", r.getInt(6)); m.put("message", r.getString(7)); m.put("problem", r.getString(8));
                    return m;
                }).optional();
    }

    private static SubmissionRow row(java.sql.ResultSet r) throws java.sql.SQLException {
        return new SubmissionRow(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getInt(6), r.getInt(7), r.getString(8), r.getLong(9), r.getTimestamp(10).toInstant());
    }

    // ---- progress and leaderboard ----

    public Map<String, int[]> progress(long userId) {
        Map<String, int[]> m = new LinkedHashMap<>();
        for (String d : List.of("easy", "medium", "hard")) m.put(d, new int[] {0, 0});
        jdbc.sql("""
                SELECT p.difficulty, count(*) AS total,
                  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM submission s WHERE s.user_id = :u AND s.problem_id = p.id AND s.status = 'ACCEPTED')) AS solved
                FROM problem p GROUP BY p.difficulty""").param("u", userId).query((r, n) -> {
            m.put(r.getString(1), new int[] {r.getInt(3), r.getInt(2)});
            return null;
        }).list();
        return m;
    }

    public List<LeaderRow> leaderboard(int limit) {
        List<LeaderRow> out = new ArrayList<>();
        int[] rank = {0};
        jdbc.sql("""
                SELECT u.username, count(*) AS solved, sum(x.points) AS points, max(x.first_ac) AS last_solve
                FROM (SELECT s.user_id, s.problem_id, min(s.created_at) AS first_ac,
                        CASE p.difficulty WHEN 'easy' THEN 10 WHEN 'medium' THEN 20 ELSE 30 END AS points
                      FROM submission s JOIN problem p ON p.id = s.problem_id WHERE s.status = 'ACCEPTED' GROUP BY s.user_id, s.problem_id, p.difficulty) x
                JOIN app_user u ON u.id = x.user_id GROUP BY u.username ORDER BY points DESC, last_solve ASC LIMIT :l""")
                .param("l", limit).query((r, n) -> {
                    out.add(new LeaderRow(++rank[0], r.getString(1), r.getInt(2), r.getInt(3)));
                    return null;
                }).list();
        return out;
    }
}

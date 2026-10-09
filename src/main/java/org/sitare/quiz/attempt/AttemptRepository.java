package org.sitare.quiz.attempt;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.sitare.quiz.common.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AttemptRepository {

    public record Attempt(long id, long quizId, long studentId, AttemptStatus status, Instant startedAt,
                          Instant deadlineAt, Instant submittedAt, BigDecimal score, BigDecimal maxScore,
                          String sessionToken, int multiSessionCount) {
    }

    public record AttemptQuestion(long questionId, int position, List<Long> optionOrder) {
    }

    public record SavedAnswer(Set<Long> selected, boolean markedForReview, long clientSeq) {
    }

    private static final String COLUMNS = """
            id, quiz_id, student_id, status, started_at, deadline_at, submitted_at, score, max_score,
            session_token, multi_session_count
            """;

    private final JdbcClient jdbc;

    public AttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Attempt> find(long quizId, long studentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attempt WHERE quiz_id = :q AND student_id = :s")
                .param("q", quizId).param("s", studentId).query(this::map).optional();
    }

    /**
     * Row lock taken while an answer is saved. It serialises saves from the same student and blocks a concurrent
     * submit, so an answer is either graded or rejected, never lost in between.
     */
    public Optional<Attempt> findForSave(long quizId, long studentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attempt WHERE quiz_id = :q AND student_id = :s FOR NO KEY UPDATE")
                .param("q", quizId).param("s", studentId).query(this::map).optional();
    }

    /** Exclusive row lock used by submit and auto-submit so an attempt is graded exactly once. */
    public Optional<Attempt> findForUpdate(long attemptId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attempt WHERE id = :id FOR UPDATE")
                .param("id", attemptId).query(this::map).optional();
    }

    /** Inserts the attempt unless one already exists for this student; returns the id either way. */
    public Optional<Long> insertIfAbsent(long quizId, long studentId, Instant startedAt, Instant deadlineAt,
                                         BigDecimal maxScore, String sessionToken) {
        return jdbc.sql("""
                        INSERT INTO attempt (quiz_id, student_id, status, started_at, deadline_at, max_score,
                                             session_token, last_seen_at)
                        VALUES (:q, :s, 'IN_PROGRESS', :start, :deadline, :max, :token, :start)
                        ON CONFLICT (quiz_id, student_id) DO NOTHING
                        RETURNING id
                        """)
                .param("q", quizId).param("s", studentId)
                .param("start", Db.ts(startedAt)).param("deadline", Db.ts(deadlineAt))
                .param("max", maxScore).param("token", sessionToken)
                .query(Long.class).optional();
    }

    public void insertQuestion(long attemptId, long questionId, int position, List<Long> optionOrder) {
        jdbc.sql("""
                        INSERT INTO attempt_question (attempt_id, question_id, position, option_order)
                        VALUES (:a, :q, :p, :o)
                        """)
                .param("a", attemptId).param("q", questionId).param("p", position).param("o", Db.joinIds(optionOrder))
                .update();
    }

    public List<AttemptQuestion> questions(long attemptId) {
        return jdbc.sql("""
                        SELECT question_id, position, option_order FROM attempt_question
                         WHERE attempt_id = :a ORDER BY position
                        """)
                .param("a", attemptId)
                .query((rs, n) -> new AttemptQuestion(rs.getLong("question_id"), rs.getInt("position"),
                        Db.splitIds(rs.getString("option_order"))))
                .list();
    }

    public Map<Long, SavedAnswer> answers(long attemptId) {
        Map<Long, SavedAnswer> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT question_id, selected_option_ids, marked_for_review, client_seq
                          FROM answer WHERE attempt_id = :a
                        """)
                .param("a", attemptId)
                .query((rs, n) -> {
                    result.put(rs.getLong("question_id"), new SavedAnswer(Db.splitIdSet(rs.getString("selected_option_ids")),
                            rs.getBoolean("marked_for_review"), rs.getLong("client_seq")));
                    return null;
                })
                .list();
        return result;
    }

    /**
     * Idempotent save (design A4): a write only wins if its client sequence number is newer than the stored one,
     * so retried or out-of-order requests never overwrite a newer answer.
     *
     * @return true if this write was applied
     */
    public boolean upsertAnswer(long attemptId, long questionId, Set<Long> selected, boolean marked, long clientSeq,
                                Instant now) {
        int rows = jdbc.sql("""
                        INSERT INTO answer (attempt_id, question_id, selected_option_ids, marked_for_review, client_seq, updated_at)
                        VALUES (:a, :q, :sel, :marked, :seq, :now)
                        ON CONFLICT (attempt_id, question_id) DO UPDATE
                           SET selected_option_ids = EXCLUDED.selected_option_ids,
                               marked_for_review   = EXCLUDED.marked_for_review,
                               client_seq          = EXCLUDED.client_seq,
                               updated_at          = EXCLUDED.updated_at
                         WHERE answer.client_seq < EXCLUDED.client_seq
                        """)
                .param("a", attemptId).param("q", questionId).param("sel", Db.joinIds(selected))
                .param("marked", marked).param("seq", clientSeq).param("now", Db.ts(now))
                .update();
        return rows > 0;
    }

    public void replaceSession(long attemptId, String token, boolean countAsSecondLogin) {
        jdbc.sql("""
                        UPDATE attempt SET session_token = :t,
                               multi_session_count = multi_session_count + :inc
                         WHERE id = :id
                        """)
                .param("t", token).param("inc", countAsSecondLogin ? 1 : 0).param("id", attemptId).update();
    }

    public void touch(long attemptId, Instant now) {
        jdbc.sql("UPDATE attempt SET last_seen_at = :now WHERE id = :id")
                .param("now", Db.ts(now)).param("id", attemptId).update();
    }

    public void finish(long attemptId, AttemptStatus status, BigDecimal score, BigDecimal maxScore, Instant at) {
        jdbc.sql("""
                        UPDATE attempt SET status = :st, score = :score, max_score = :max, submitted_at = :at,
                               version = version + 1
                         WHERE id = :id AND status = 'IN_PROGRESS'
                        """)
                .param("st", status.name()).param("score", score).param("max", maxScore).param("at", Db.ts(at))
                .param("id", attemptId).update();
    }

    public void extendAll(long quizId, int minutes) {
        jdbc.sql("""
                        UPDATE attempt SET deadline_at = deadline_at + make_interval(mins => :m)
                         WHERE quiz_id = :q AND status = 'IN_PROGRESS'
                        """)
                .param("m", minutes).param("q", quizId).update();
    }

    public void extendOne(long quizId, long studentId, int minutes) {
        jdbc.sql("""
                        UPDATE attempt SET deadline_at = deadline_at + make_interval(mins => :m)
                         WHERE quiz_id = :q AND student_id = :s AND status = 'IN_PROGRESS'
                        """)
                .param("m", minutes).param("q", quizId).param("s", studentId).update();
    }

    public List<Long> overdueIds(Instant cutoff, int limit) {
        return jdbc.sql("""
                        SELECT id FROM attempt WHERE status = 'IN_PROGRESS' AND deadline_at < :cutoff
                         ORDER BY deadline_at LIMIT :lim
                        """)
                .param("cutoff", Db.ts(cutoff)).param("lim", limit).query(Long.class).list();
    }

    public List<Long> openIds(long quizId) {
        return jdbc.sql("SELECT id FROM attempt WHERE quiz_id = :q AND status = 'IN_PROGRESS'")
                .param("q", quizId).query(Long.class).list();
    }

    private Attempt map(ResultSet rs, int rowNum) throws SQLException {
        return new Attempt(
                rs.getLong("id"),
                rs.getLong("quiz_id"),
                rs.getLong("student_id"),
                AttemptStatus.valueOf(rs.getString("status")),
                Db.instant(rs, "started_at"),
                Db.instant(rs, "deadline_at"),
                Db.instant(rs, "submitted_at"),
                rs.getBigDecimal("score"),
                rs.getBigDecimal("max_score"),
                rs.getString("session_token"),
                rs.getInt("multi_session_count"));
    }
}

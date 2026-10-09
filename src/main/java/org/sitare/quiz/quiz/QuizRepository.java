package org.sitare.quiz.quiz;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.sitare.quiz.common.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QuizRepository {

    public record RosterEntry(long studentId, String rollNo, String name, String email, int extraMinutes) {
    }

    private static final String COLUMNS = """
            id, faculty_id, code, title, course, starts_at, duration_min, late_join_min, extension_min,
            negative_marking, negative_fraction, webcam_enabled, snapshot_retention_days, answers_shared, status
            """;

    private final JdbcClient jdbc;

    public QuizRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long facultyId, String code, QuizSettings s) {
        return jdbc.sql("""
                        INSERT INTO quiz (faculty_id, code, title, course, starts_at, duration_min, late_join_min,
                                          negative_marking, negative_fraction, webcam_enabled, snapshot_retention_days)
                        VALUES (:faculty, :code, :title, :course, :starts, :duration, :late,
                                :neg, :frac, :webcam, :retention)
                        RETURNING id
                        """)
                .param("faculty", facultyId)
                .param("code", code)
                .params(settingsParams(s))
                .query(Long.class)
                .single();
    }

    public void updateSettings(long quizId, QuizSettings s) {
        jdbc.sql("""
                        UPDATE quiz SET title = :title, course = :course, starts_at = :starts, duration_min = :duration,
                               late_join_min = :late, negative_marking = :neg, negative_fraction = :frac,
                               webcam_enabled = :webcam, snapshot_retention_days = :retention
                         WHERE id = :id
                        """)
                .param("id", quizId)
                .params(settingsParams(s))
                .update();
    }

    private static java.util.Map<String, Object> settingsParams(QuizSettings s) {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("title", s.title());
        p.put("course", s.course());
        p.put("starts", Db.ts(s.startsAt()));
        p.put("duration", s.durationMin());
        p.put("late", s.lateJoinMin());
        p.put("neg", s.negativeMarking());
        p.put("frac", s.negativeFraction());
        p.put("webcam", s.webcamEnabled());
        p.put("retention", s.snapshotRetentionDays());
        return p;
    }

    public Optional<Quiz> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM quiz WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<Quiz> findByCode(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM quiz WHERE upper(code) = upper(:code)")
                .param("code", code.trim()).query(this::map).optional();
    }

    public List<Quiz> listByFaculty(long facultyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM quiz WHERE faculty_id = :f ORDER BY starts_at DESC")
                .param("f", facultyId).query(this::map).list();
    }

    public boolean codeExists(String code) {
        return jdbc.sql("SELECT count(*) FROM quiz WHERE code = :code").param("code", code)
                .query(Long.class).single() > 0;
    }

    public void setStatus(long quizId, QuizStatus status) {
        jdbc.sql("UPDATE quiz SET status = :s WHERE id = :id").param("s", status.name()).param("id", quizId).update();
    }

    public void setAnswersShared(long quizId, boolean shared) {
        jdbc.sql("UPDATE quiz SET answers_shared = :s WHERE id = :id").param("s", shared).param("id", quizId).update();
    }

    public void addExtension(long quizId, int minutes) {
        jdbc.sql("UPDATE quiz SET extension_min = extension_min + :m WHERE id = :id")
                .param("m", minutes).param("id", quizId).update();
    }

    // ---- questions in a quiz ----

    public List<Long> questionIds(long quizId) {
        return jdbc.sql("SELECT question_id FROM quiz_question WHERE quiz_id = :q ORDER BY position")
                .param("q", quizId).query(Long.class).list();
    }

    public void replaceQuestions(long quizId, List<Long> questionIds) {
        jdbc.sql("DELETE FROM quiz_question WHERE quiz_id = :q").param("q", quizId).update();
        int position = 1;
        for (Long qid : questionIds) {
            jdbc.sql("INSERT INTO quiz_question (quiz_id, question_id, position) VALUES (:q, :qid, :p)")
                    .param("q", quizId).param("qid", qid).param("p", position++).update();
        }
    }

    // ---- roster ----

    public void upsertRoster(long quizId, long studentId, String otpHash) {
        jdbc.sql("""
                        INSERT INTO quiz_roster (quiz_id, student_id, otp_hash) VALUES (:q, :s, :h)
                        ON CONFLICT (quiz_id, student_id) DO UPDATE SET otp_hash = EXCLUDED.otp_hash
                        """)
                .param("q", quizId).param("s", studentId).param("h", otpHash).update();
    }

    public List<RosterEntry> roster(long quizId) {
        return jdbc.sql("""
                        SELECT s.id, s.roll_no, s.name, s.email, r.extra_minutes
                          FROM quiz_roster r JOIN student s ON s.id = r.student_id
                         WHERE r.quiz_id = :q
                         ORDER BY s.roll_no
                        """)
                .param("q", quizId)
                .query((rs, n) -> new RosterEntry(rs.getLong("id"), rs.getString("roll_no"), rs.getString("name"),
                        rs.getString("email"), rs.getInt("extra_minutes")))
                .list();
    }

    public Optional<String> otpHash(long quizId, long studentId) {
        return jdbc.sql("SELECT otp_hash FROM quiz_roster WHERE quiz_id = :q AND student_id = :s")
                .param("q", quizId).param("s", studentId).query(String.class).optional();
    }

    public int extraMinutes(long quizId, long studentId) {
        return jdbc.sql("SELECT extra_minutes FROM quiz_roster WHERE quiz_id = :q AND student_id = :s")
                .param("q", quizId).param("s", studentId).query(Integer.class).optional().orElse(0);
    }

    public void addStudentExtra(long quizId, long studentId, int minutes) {
        jdbc.sql("UPDATE quiz_roster SET extra_minutes = extra_minutes + :m WHERE quiz_id = :q AND student_id = :s")
                .param("m", minutes).param("q", quizId).param("s", studentId).update();
    }

    private Quiz map(ResultSet rs, int rowNum) throws SQLException {
        return new Quiz(
                rs.getLong("id"),
                rs.getLong("faculty_id"),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("course"),
                Db.instant(rs, "starts_at"),
                rs.getInt("duration_min"),
                rs.getInt("late_join_min"),
                rs.getInt("extension_min"),
                rs.getBoolean("negative_marking"),
                rs.getBigDecimal("negative_fraction"),
                rs.getBoolean("webcam_enabled"),
                rs.getInt("snapshot_retention_days"),
                rs.getBoolean("answers_shared"),
                QuizStatus.valueOf(rs.getString("status")));
    }
}

package org.sitare.quiz.bank;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionRepository {

    private final JdbcClient jdbc;

    public QuestionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long facultyId, QuestionDraft draft) {
        long id = jdbc.sql("""
                        INSERT INTO question (faculty_id, topic, type, stem, marks)
                        VALUES (:f, :topic, :type, :stem, :marks) RETURNING id
                        """)
                .param("f", facultyId)
                .param("topic", draft.topic())
                .param("type", draft.type().name())
                .param("stem", draft.stem())
                .param("marks", draft.marks())
                .query(Long.class)
                .single();
        int position = 1;
        for (QuestionDraft.OptionDraft o : draft.options()) {
            jdbc.sql("INSERT INTO question_option (question_id, text, is_correct, position) VALUES (:q, :t, :c, :p)")
                    .param("q", id).param("t", o.text()).param("c", o.correct()).param("p", position++).update();
        }
        return id;
    }

    public void deactivate(long facultyId, long questionId) {
        jdbc.sql("UPDATE question SET active = FALSE WHERE id = :id AND faculty_id = :f")
                .param("id", questionId).param("f", facultyId).update();
    }

    public List<Question> listActive(long facultyId) {
        List<Long> ids = jdbc.sql("SELECT id FROM question WHERE faculty_id = :f AND active ORDER BY topic, id")
                .param("f", facultyId).query(Long.class).list();
        return findByIds(ids);
    }

    public Optional<Question> findById(long id) {
        List<Question> found = findByIds(List.of(id));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Loads questions with their options, in the order of the ids given. */
    public List<Question> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        record Row(long id, long facultyId, String topic, String type, String stem, BigDecimal marks, boolean active) {
        }
        Map<Long, Row> rows = new LinkedHashMap<>();
        jdbc.sql("SELECT id, faculty_id, topic, type, stem, marks, active FROM question WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getLong("faculty_id"), rs.getString("topic"),
                        rs.getString("type"), rs.getString("stem"), rs.getBigDecimal("marks"), rs.getBoolean("active")))
                .list()
                .forEach(r -> rows.put(r.id(), r));

        Map<Long, List<Question.Option>> options = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT id, question_id, text, is_correct, position FROM question_option
                         WHERE question_id IN (:ids) ORDER BY question_id, position
                        """)
                .param("ids", ids)
                .query((rs, n) -> {
                    options.computeIfAbsent(rs.getLong("question_id"), k -> new ArrayList<>())
                            .add(new Question.Option(rs.getLong("id"), rs.getString("text"),
                                    rs.getBoolean("is_correct"), rs.getInt("position")));
                    return null;
                })
                .list();

        List<Question> result = new ArrayList<>();
        for (Long id : ids) {
            Row r = rows.get(id);
            if (r != null) {
                result.add(new Question(r.id(), r.facultyId(), r.topic(), QuestionType.valueOf(r.type()), r.stem(),
                        r.marks(), r.active(), List.copyOf(options.getOrDefault(id, List.of()))));
            }
        }
        return result;
    }
}

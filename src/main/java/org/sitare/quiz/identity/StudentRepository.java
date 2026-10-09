package org.sitare.quiz.identity;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StudentRepository {

    public record Student(long id, String rollNo, String name, String email) {
    }

    private final JdbcClient jdbc;

    public StudentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the student or refreshes their name and email; returns the id. */
    public long upsert(String rollNo, String name, String email) {
        return jdbc.sql("""
                        INSERT INTO student (roll_no, name, email) VALUES (:roll, :name, :email)
                        ON CONFLICT (roll_no) DO UPDATE
                           SET name = EXCLUDED.name,
                               email = COALESCE(EXCLUDED.email, student.email)
                        RETURNING id
                        """)
                .param("roll", rollNo)
                .param("name", name)
                .param("email", email == null || email.isBlank() ? null : email.trim())
                .query(Long.class)
                .single();
    }

    public Optional<Student> findByRoll(String rollNo) {
        return jdbc.sql("SELECT id, roll_no, name, email FROM student WHERE upper(roll_no) = upper(:roll)")
                .param("roll", rollNo.trim())
                .query((rs, n) -> new Student(rs.getLong("id"), rs.getString("roll_no"), rs.getString("name"),
                        rs.getString("email")))
                .optional();
    }
}

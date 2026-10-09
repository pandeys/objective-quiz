package org.sitare.quiz.identity;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FacultyRepository {

    private final JdbcClient jdbc;

    public FacultyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FacultyPrincipal> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, name, password_hash FROM faculty WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query((rs, n) -> new FacultyPrincipal(rs.getLong("id"), rs.getString("email"),
                        rs.getString("name"), rs.getString("password_hash")))
                .optional();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM faculty").query(Long.class).single();
    }

    public void insert(String email, String name, String passwordHash) {
        jdbc.sql("INSERT INTO faculty (email, name, password_hash) VALUES (:email, :name, :hash)")
                .param("email", email.trim().toLowerCase())
                .param("name", name)
                .param("hash", passwordHash)
                .update();
    }
}

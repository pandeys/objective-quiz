package org.sitare.quiz.identity;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** The logged-in faculty member. */
public class FacultyPrincipal extends User {

    private final long id;
    private final String displayName;

    public FacultyPrincipal(long id, String email, String displayName, String passwordHash) {
        super(email, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_FACULTY")));
        this.id = id;
        this.displayName = displayName;
    }

    public long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }
}

package org.sitare.quiz.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class FacultyUserDetailsService implements UserDetailsService {

    private final FacultyRepository faculty;

    public FacultyUserDetailsService(FacultyRepository faculty) {
        this.faculty = faculty;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return faculty.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("Unknown faculty"));
    }
}

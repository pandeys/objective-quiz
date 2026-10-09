package org.sitare.quiz.identity;

import org.sitare.quiz.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first faculty account from APP_FACULTY_EMAIL / APP_FACULTY_PASSWORD when none exists. */
@Component
public class BootstrapFaculty implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapFaculty.class);

    private final FacultyRepository faculty;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public BootstrapFaculty(FacultyRepository faculty, PasswordEncoder encoder, AppProperties props) {
        this.faculty = faculty;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Bootstrap b = props.bootstrap();
        if (b == null || isBlank(b.facultyEmail()) || isBlank(b.facultyPassword())) {
            return;
        }
        if (faculty.count() > 0) {
            return;
        }
        if (b.facultyPassword().length() < 10) {
            log.warn("APP_FACULTY_PASSWORD must be at least 10 characters; no faculty account was created.");
            return;
        }
        String name = isBlank(b.facultyName()) ? "Faculty" : b.facultyName();
        faculty.insert(b.facultyEmail(), name, encoder.encode(b.facultyPassword()));
        log.info("Created first faculty account for {}", b.facultyEmail());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

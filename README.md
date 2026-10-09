# Sitare Quiz

A small web app for running timed objective quizzes for one class (up to about 60 students) at Sitare University.
It follows the *Sitare Quiz — Software Architecture Document (HLD & LLD)*.

This first release covers the **core quiz**:

- Faculty login, question bank (single-correct, multiple-correct, true/false) with CSV or Excel import
- Quiz setup: start time, duration, late-join window, optional negative marking (off by default; 0.5 of the question's marks per wrong answer)
- Class roster upload and printable one-time passwords
- Student quiz page: one question at a time, free back-and-forth navigation, change or clear any answer, mark for review,
  instant auto-save with offline retry, server-controlled timer, auto-submit at time end
- Server-side grading, instant score for the student, correct answers hidden until faculty share them
- One active session per student (a second login takes over and is counted)
- Live time extension (whole class or one student), close quiz, results table and Excel export of total marks

Browser proctoring (fullscreen and tab-switch events, webcam snapshots, flag review) is the next release.

## Tech

Java 21 · Spring Boot 3.3 (Web, Security, JDBC, Thymeleaf) · PostgreSQL 16 · Flyway · Apache POI · plain JavaScript.

## Run locally

```bash
docker compose up -d                        # PostgreSQL on localhost:5432
export APP_FACULTY_EMAIL=you@sitare.org     # first faculty account, created on first start
export APP_FACULTY_PASSWORD='choose-a-long-password'
mvn spring-boot:run
```

Open http://localhost:8080, log in as faculty, then:

1. **Question bank** → import `question-template.csv` (download link on the page) or add questions by hand.
2. **Quizzes** → create a quiz, pick questions, upload the roster (`roll_no,name,email`), print the password slips, publish.
3. Students open `/student/login`, enter the quiz code, roll number and their password.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/sitare_quiz` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `sitare` / `sitare` | Database login |
| `APP_FACULTY_EMAIL`, `APP_FACULTY_PASSWORD`, `APP_FACULTY_NAME` | empty | Creates the first faculty account if none exists (password ≥ 10 characters) |
| `app.timezone` | `Asia/Kolkata` | Time zone used to show and enter quiz times |
| `app.quiz.grace-seconds` | `5` | Seconds after the deadline during which an in-flight save is still accepted |

## Tests

```bash
mvn verify      # unit tests + an end-to-end test against PostgreSQL (needs Docker for Testcontainers)
```

To run the same on every push with GitHub Actions, copy `ci/github-actions-ci.yml` to `.github/workflows/ci.yml`
(the access token used for the first push could not create workflow files). It builds, tests and keeps the jar as an artifact.

## Deploy (Oracle Cloud Always Free VM)

See [`deploy/README.md`](deploy/README.md): systemd service, Caddy for HTTPS, nightly `pg_dump` backups, and the
quiz-day checklist.

## Project layout

```
org.sitare.quiz
├── common     security, errors, clock, JDBC helpers
├── identity   faculty and student login, one-time passwords, rate limiting
├── bank       questions, CSV/Excel import
├── quiz       quiz settings, roster, publish, extensions
├── attempt    start, save, submit, auto-submit, per-student randomisation
├── grading    scoring rules
├── results    results table, Excel export
└── web        page controllers and the student JSON API
```

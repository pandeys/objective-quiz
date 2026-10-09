-- Sitare Quiz core schema (v1: question bank, quizzes, attempts, grading)

CREATE TABLE faculty (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE student (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    roll_no VARCHAR(50)  NOT NULL UNIQUE,
    name    VARCHAR(255) NOT NULL,
    email   VARCHAR(255)
);

CREATE TABLE question (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    faculty_id BIGINT       NOT NULL REFERENCES faculty (id),
    topic      VARCHAR(100) NOT NULL DEFAULT 'General',
    type       VARCHAR(20)  NOT NULL CHECK (type IN ('SINGLE', 'MULTI', 'TRUE_FALSE')),
    stem       TEXT         NOT NULL,
    marks      NUMERIC(6, 2) NOT NULL DEFAULT 1 CHECK (marks > 0),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_question_faculty_topic ON question (faculty_id, topic);

CREATE TABLE question_option (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_id BIGINT  NOT NULL REFERENCES question (id) ON DELETE CASCADE,
    text        TEXT    NOT NULL,
    is_correct  BOOLEAN NOT NULL DEFAULT FALSE,
    position    INT     NOT NULL
);
CREATE INDEX idx_option_question ON question_option (question_id);

CREATE TABLE quiz (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    faculty_id              BIGINT        NOT NULL REFERENCES faculty (id),
    code                    VARCHAR(12)   NOT NULL UNIQUE,
    title                   VARCHAR(255)  NOT NULL,
    course                  VARCHAR(255),
    starts_at               TIMESTAMPTZ   NOT NULL,
    duration_min            INT           NOT NULL DEFAULT 30 CHECK (duration_min > 0),
    late_join_min           INT           NOT NULL DEFAULT 10 CHECK (late_join_min >= 0),
    extension_min           INT           NOT NULL DEFAULT 0 CHECK (extension_min >= 0),
    negative_marking        BOOLEAN       NOT NULL DEFAULT FALSE,
    negative_fraction       NUMERIC(4, 2) NOT NULL DEFAULT 0.50,
    webcam_enabled          BOOLEAN       NOT NULL DEFAULT FALSE,
    snapshot_retention_days INT           NOT NULL DEFAULT 30,
    answers_shared          BOOLEAN       NOT NULL DEFAULT FALSE,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_quiz_status_start ON quiz (status, starts_at);

CREATE TABLE quiz_question (
    quiz_id     BIGINT NOT NULL REFERENCES quiz (id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES question (id),
    position    INT    NOT NULL,
    PRIMARY KEY (quiz_id, question_id)
);

CREATE TABLE quiz_roster (
    quiz_id        BIGINT       NOT NULL REFERENCES quiz (id) ON DELETE CASCADE,
    student_id     BIGINT       NOT NULL REFERENCES student (id),
    otp_hash       VARCHAR(100) NOT NULL,
    extra_minutes  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (quiz_id, student_id)
);

CREATE TABLE attempt (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    quiz_id          BIGINT        NOT NULL REFERENCES quiz (id),
    student_id       BIGINT        NOT NULL REFERENCES student (id),
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'AUTO_SUBMITTED')),
    started_at       TIMESTAMPTZ   NOT NULL,
    deadline_at      TIMESTAMPTZ   NOT NULL,
    submitted_at     TIMESTAMPTZ,
    score            NUMERIC(8, 2),
    max_score        NUMERIC(8, 2) NOT NULL,
    webcam_available BOOLEAN       NOT NULL DEFAULT FALSE,
    session_token    VARCHAR(64)   NOT NULL,
    multi_session_count INT        NOT NULL DEFAULT 0,
    last_seen_at     TIMESTAMPTZ,
    version          BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (quiz_id, student_id)
);
CREATE INDEX idx_attempt_quiz_status ON attempt (quiz_id, status);
CREATE INDEX idx_attempt_status_deadline ON attempt (status, deadline_at);

CREATE TABLE attempt_question (
    attempt_id   BIGINT NOT NULL REFERENCES attempt (id) ON DELETE CASCADE,
    question_id  BIGINT NOT NULL REFERENCES question (id),
    position     INT    NOT NULL,
    option_order VARCHAR(255) NOT NULL, -- comma-separated option ids in display order
    PRIMARY KEY (attempt_id, question_id)
);

CREATE TABLE answer (
    attempt_id          BIGINT       NOT NULL REFERENCES attempt (id) ON DELETE CASCADE,
    question_id         BIGINT       NOT NULL REFERENCES question (id),
    selected_option_ids VARCHAR(255) NOT NULL DEFAULT '', -- comma-separated; empty = cleared
    marked_for_review   BOOLEAN      NOT NULL DEFAULT FALSE,
    client_seq          BIGINT       NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (attempt_id, question_id)
);

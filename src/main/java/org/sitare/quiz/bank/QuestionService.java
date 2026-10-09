package org.sitare.quiz.bank;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {

    private final QuestionRepository questions;

    public QuestionService(QuestionRepository questions) {
        this.questions = questions;
    }

    public List<Question> list(long facultyId) {
        return questions.listActive(facultyId);
    }

    @Transactional
    public long create(long facultyId, QuestionDraft draft) {
        return questions.insert(facultyId, draft);
    }

    /**
     * Imports every row or none: if any row has an error, nothing is saved and all errors are returned.
     */
    @Transactional
    public QuestionRowParser.ParseResult importFile(long facultyId, String filename, InputStream in) throws IOException {
        QuestionRowParser.ParseResult result = QuestionRowParser.parse(SpreadsheetReader.read(filename, in));
        if (result.ok()) {
            for (QuestionDraft draft : result.questions()) {
                questions.insert(facultyId, draft);
            }
        }
        return result;
    }

    @Transactional
    public void remove(long facultyId, long questionId) {
        questions.deactivate(facultyId, questionId);
    }
}

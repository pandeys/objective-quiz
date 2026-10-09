package org.sitare.quiz.attempt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Per-student, reproducible shuffle of questions and options (design A1).
 * The same quiz and student always get the same order, which keeps the order stable across
 * refreshes and lets an audit reproduce exactly what a student saw.
 */
public final class QuestionOrderRandomizer {

    private QuestionOrderRandomizer() {
    }

    /**
     * @param optionIdsByQuestion question id to its option ids, in the quiz's own order
     * @return question id to its shuffled option ids, iterating in the shuffled question order
     */
    public static Map<Long, List<Long>> shuffle(long quizId, long studentId,
                                                Map<Long, List<Long>> optionIdsByQuestion) {
        Random random = new Random(seed(quizId, studentId));
        List<Long> questionIds = new ArrayList<>(optionIdsByQuestion.keySet());
        Collections.sort(questionIds);
        Collections.shuffle(questionIds, random);

        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (Long qid : questionIds) {
            List<Long> options = new ArrayList<>(optionIdsByQuestion.get(qid));
            Collections.sort(options);
            Collections.shuffle(options, random);
            result.put(qid, List.copyOf(options));
        }
        return result;
    }

    static long seed(long quizId, long studentId) {
        long h = 1125899906842597L;
        h = 31 * h + quizId;
        h = 31 * h + studentId;
        return h ^ (h >>> 33);
    }
}

package org.sitare.quiz.web;

import jakarta.servlet.http.HttpServletResponse;

import org.sitare.quiz.common.ApiException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** Shows a friendly page for business errors raised while rendering HTML pages. */
@ControllerAdvice(assignableTypes = {FacultyQuizController.class, FacultyQuestionController.class,
        StudentPageController.class, HomeController.class})
public class PageErrorHandler {

    @ExceptionHandler(ApiException.class)
    public String handle(ApiException e, Model model, HttpServletResponse response) {
        response.setStatus(e.status().value());
        model.addAttribute("message", e.getMessage());
        return "problem";
    }
}

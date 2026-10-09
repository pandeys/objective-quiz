package org.sitare.quiz.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.sitare.quiz.bank.QuestionRowParser;
import org.sitare.quiz.bank.QuestionService;
import org.sitare.quiz.identity.FacultyPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/faculty/questions")
public class FacultyQuestionController {

    private final QuestionService questions;

    public FacultyQuestionController(QuestionService questions) {
        this.questions = questions;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal FacultyPrincipal me, Model model) {
        model.addAttribute("questions", questions.list(me.getId()));
        model.addAttribute("me", me);
        return "faculty/questions";
    }

    /** Adds one question from the form: options one per line, correct options as numbers like "2" or "1;3". */
    @PostMapping
    public String create(@AuthenticationPrincipal FacultyPrincipal me,
                         @RequestParam("topic") String topic,
                         @RequestParam("type") String type,
                         @RequestParam("stem") String stem,
                         @RequestParam(name = "marks", defaultValue = "1") String marks,
                         @RequestParam(name = "options", defaultValue = "") String options,
                         @RequestParam("correct") String correct,
                         RedirectAttributes flash) {
        List<String> header = new ArrayList<>(List.of("topic", "type", "question", "marks", "correct"));
        List<String> row = new ArrayList<>(List.of(topic, type, stem, marks, correct));
        int n = 1;
        for (String line : options.split("\\R")) {
            if (!line.isBlank() && n <= QuestionRowParser.MAX_OPTIONS) {
                header.add("option" + n++);
                row.add(line.trim());
            }
        }
        QuestionRowParser.ParseResult parsed = QuestionRowParser.parse(List.of(header, row));
        if (!parsed.ok()) {
            flash.addFlashAttribute("error", String.join(" ", parsed.errors()).replace("Row 2: ", ""));
            return "redirect:/faculty/questions";
        }
        questions.create(me.getId(), parsed.questions().get(0));
        flash.addFlashAttribute("message", "Question added.");
        return "redirect:/faculty/questions";
    }

    @PostMapping("/import")
    public String importFile(@AuthenticationPrincipal FacultyPrincipal me, @RequestParam("file") MultipartFile file,
                             RedirectAttributes flash) throws IOException {
        if (file.isEmpty()) {
            flash.addFlashAttribute("error", "Choose a CSV or Excel file to import.");
            return "redirect:/faculty/questions";
        }
        QuestionRowParser.ParseResult result = questions.importFile(me.getId(), file.getOriginalFilename(),
                file.getInputStream());
        if (result.ok()) {
            flash.addFlashAttribute("message", "Imported " + result.questions().size() + " questions.");
        } else {
            flash.addFlashAttribute("importErrors", result.errors());
            flash.addFlashAttribute("error", "Nothing was imported. Fix these rows and try again.");
        }
        return "redirect:/faculty/questions";
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal FacultyPrincipal me, @PathVariable long id, RedirectAttributes flash) {
        questions.remove(me.getId(), id);
        flash.addFlashAttribute("message", "Question removed from the bank. Quizzes that already use it keep it.");
        return "redirect:/faculty/questions";
    }
}

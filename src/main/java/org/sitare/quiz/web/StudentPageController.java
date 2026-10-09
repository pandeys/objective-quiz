package org.sitare.quiz.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.sitare.quiz.identity.StudentAuthService;
import org.sitare.quiz.identity.StudentPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class StudentPageController {

    private final StudentAuthService auth;
    private final SecurityContextRepository contextRepository;

    public StudentPageController(StudentAuthService auth, SecurityContextRepository contextRepository) {
        this.auth = auth;
        this.contextRepository = contextRepository;
    }

    @GetMapping("/student/login")
    public String loginPage(@RequestParam(name = "code", required = false) String code, Model model) {
        model.addAttribute("code", code == null ? "" : code);
        return "student/login";
    }

    @PostMapping("/student/login")
    public String login(@RequestParam("code") String code, @RequestParam("roll") String roll,
                        @RequestParam("otp") String otp, HttpServletRequest request, HttpServletResponse response,
                        Model model) {
        StudentAuthService.LoginResult result = auth.login(code, roll, otp);
        if (result instanceof StudentAuthService.Failure failure) {
            model.addAttribute("error", failure.message());
            model.addAttribute("code", code);
            model.addAttribute("roll", roll);
            return "student/login";
        }
        StudentPrincipal principal = ((StudentAuthService.Success) result).principal();

        // Fresh session on login (prevents session fixation).
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        request.getSession(true);

        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return "redirect:/student/quiz";
    }

    @GetMapping("/student/quiz")
    public String quizPage() {
        return "student/quiz";
    }
}

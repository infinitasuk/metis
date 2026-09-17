package com.metis.web;

import com.metis.engine.api.AuthenticationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LoginController {

    private final AuthenticationContext authentication;

    public LoginController(AuthenticationContext authentication) {
        this.authentication = authentication;
    }

    @GetMapping("/login")
    public String login(HttpServletRequest request, @RequestParam(defaultValue = "/") String next, Model model) {
        if (authentication.current(request).isPresent()) return "redirect:" + safeNext(next);
        request.getSession(true);
        model.addAttribute("users", authentication.users().findActiveUsers());
        model.addAttribute("next", safeNext(next));
        return "login";
    }

    @PostMapping("/login")
    public String selectUser(HttpServletRequest request, @RequestParam long userId,
                             @RequestParam(defaultValue = "/") String next) {
        authentication.login(request, userId);
        return "redirect:" + safeNext(next);
    }

    @GetMapping("/logout")
    public String logout(HttpServletRequest request) {
        authentication.logout(request);
        return "redirect:/login";
    }

    private static String safeNext(String next) {
        return next != null && next.startsWith("/") && !next.startsWith("//") ? next : "/";
    }
}

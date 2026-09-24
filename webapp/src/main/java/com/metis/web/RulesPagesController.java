package com.metis.web;

import com.metis.engine.api.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Controller
public class RulesPagesController {
    private final RulesService rules;
    private final AuthenticationContext authentication;
    public RulesPagesController(RulesService rules,AuthenticationContext authentication) { this.rules=rules;this.authentication=authentication; }
    @GetMapping("/rules") public String catalogue(HttpServletRequest request) { authentication.require(request);return "rules-catalogue"; }
    @GetMapping("/rules/sessions/{id}") public String session(@PathVariable UUID id,HttpServletRequest request,Model model) {
        try { rules.state(id,authentication.require(request)); }
        catch(RulesService.Failure error) { throw new ResponseStatusException(HttpStatus.valueOf(error.status()),error.code()); }
        model.addAttribute("rulesSessionId",id.toString());return "rules-session";
    }
}

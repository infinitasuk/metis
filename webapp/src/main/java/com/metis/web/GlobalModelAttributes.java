package com.metis.web;

import com.metis.engine.api.AuthenticationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAttributes {

    private final AuthenticationContext authentication;

    public GlobalModelAttributes(AuthenticationContext authentication) {
        this.authentication = authentication;
    }

    @ModelAttribute("currentUser")
    public Object currentUser(HttpServletRequest request) {
        return authentication.current(request).orElse(null);
    }
}

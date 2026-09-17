package com.metis.engine.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class AuthenticationContext {

    public static final String USER_ID_ATTRIBUTE = "metis.user.id";

    private final UserRepository users;

    public AuthenticationContext(UserRepository users) {
        this.users = users;
    }

    public Optional<MetisUser> current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return Optional.empty();
        Object value = session.getAttribute(USER_ID_ATTRIBUTE);
        if (!(value instanceof Number number)) return Optional.empty();
        return users.findActive(number.longValue());
    }

    public MetisUser require(HttpServletRequest request) {
        return current(request).orElseThrow(() -> new UnauthenticatedException());
    }

    public void login(HttpServletRequest request, long userId) {
        MetisUser user = users.findActive(userId).orElseThrow(() -> new IllegalArgumentException("User does not exist"));
        request.getSession(true).setAttribute(USER_ID_ATTRIBUTE, user.userId());
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    public UserRepository users() {
        return users;
    }

    public static class UnauthenticatedException extends RuntimeException {}
}

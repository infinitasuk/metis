package com.metis.web;

import com.metis.engine.api.AuthenticationContext;
import com.metis.engine.api.BattleRepository;
import com.metis.engine.api.MetisUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class WebAuthInterceptor implements HandlerInterceptor {

    private final AuthenticationContext authentication;
    private final BattleRepository battle;

    public WebAuthInterceptor(AuthenticationContext authentication, BattleRepository battle) {
        this.authentication = authentication;
        this.battle = battle;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = request.getRequestURI();
        if (path.equals("/login") || path.equals("/logout") || path.startsWith("/styles.css")
                || path.startsWith("/favicon") || path.equals("/error")) {
            return true;
        }
        Optional<MetisUser> current = authentication.current(request);
        if (current.isEmpty()) {
            if (path.startsWith("/api/")) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Login required");
            } else {
                String next = URLEncoder.encode(path + (request.getQueryString() == null
                        ? "" : "?" + request.getQueryString()), StandardCharsets.UTF_8);
                response.sendRedirect("/login?next=" + next);
            }
            return false;
        }
        MetisUser user = current.get();
        if (path.startsWith("/admin") || path.startsWith("/api/v1/admin")
                || path.startsWith("/api/v1/test-fixtures")) {
            if (!user.admin()) response.sendError(HttpServletResponse.SC_FORBIDDEN, "Administrator access required");
            return user.admin();
        }
        Long sessionId = sessionId(path);
        if (sessionId == null) {
            sessionId = resourceSessionId(path);
            if (isSessionResource(path) && sessionId == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Resource does not exist");
                return false;
            }
        }
        if (sessionId != null) {
            boolean edit = !"GET".equalsIgnoreCase(request.getMethod());
            boolean allowed = edit ? battle.canEditSession(sessionId, user) : battle.canViewSession(sessionId, user);
            if (!allowed) response.sendError(HttpServletResponse.SC_FORBIDDEN, "You do not have access to this game");
            return allowed;
        }
        return true;
    }

    private static Long sessionId(String path) {
        String marker = "/api/v1/sessions/";
        if (path.startsWith("/simulator/sessions/")) marker = "/simulator/sessions/";
        else if (!path.startsWith(marker)) return null;
        String value = path.substring(marker.length()).split("/", 2)[0];
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long resourceSessionId(String path) {
        String[] parts = path.split("/");
        if (parts.length < 5) return null;
        try {
            long resourceId = Long.parseLong(parts[4]);
            if (path.startsWith("/api/v1/players/")) return battle.sessionIdForPlayer(resourceId).orElse(null);
            if (path.startsWith("/api/v1/fleets/")) return battle.sessionIdForFleet(resourceId).orElse(null);
            if (path.startsWith("/api/v1/units/")) return battle.sessionIdForUnit(resourceId).orElse(null);
            if (path.startsWith("/api/v1/boards/")) return battle.sessionIdForBoard(resourceId).orElse(null);
            if (path.startsWith("/api/v1/turns/")) return battle.sessionIdForTurn(resourceId).orElse(null);
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static boolean isSessionResource(String path) {
        return path.startsWith("/api/v1/players/") || path.startsWith("/api/v1/fleets/")
                || path.startsWith("/api/v1/units/") || path.startsWith("/api/v1/boards/")
                || path.startsWith("/api/v1/turns/");
    }
}

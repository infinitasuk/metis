package com.metis.web;

import com.metis.engine.api.AuthenticationContext;
import com.metis.engine.api.BattleRepository;
import com.metis.engine.api.MetisUser;
import com.metis.engine.api.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebAuthInterceptorTest {

    @Test
    void redirectsUnauthenticatedBrowserRequestsToLogin() throws Exception {
        AuthenticationContext authentication = new AuthenticationContext(new FakeUsers());
        WebAuthInterceptor interceptor = new WebAuthInterceptor(authentication, new FakeBattle());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/simulator");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertTrue(response.getRedirectedUrl().startsWith("/login?next="));
    }

    @Test
    void regularUsersCannotEnterAdminRoutes() throws Exception {
        AuthenticationContext authentication = new AuthenticationContext(new FakeUsers());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin");
        authentication.login(request, 1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(new WebAuthInterceptor(authentication, new FakeBattle())
                .preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void invitedUsersCanViewButCannotMutatePrivateSessions() throws Exception {
        AuthenticationContext authentication = new AuthenticationContext(new FakeUsers());
        FakeBattle battle = new FakeBattle();

        MockHttpServletRequest viewRequest = new MockHttpServletRequest("GET", "/simulator/sessions/42");
        authentication.login(viewRequest, 2);
        assertTrue(new WebAuthInterceptor(authentication, battle)
                .preHandle(viewRequest, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest mutateRequest = new MockHttpServletRequest("POST", "/api/v1/sessions/42/actions");
        authentication.login(mutateRequest, 2);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(new WebAuthInterceptor(authentication, battle)
                .preHandle(mutateRequest, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    private static final class FakeUsers extends UserRepository {
        private FakeUsers() {
            super(null);
        }

        @Override
        public Optional<MetisUser> findActive(long userId) {
            return userId == 1 ? Optional.of(new MetisUser(1, "alice", "Alice", "user"))
                    : userId == 2 ? Optional.of(new MetisUser(2, "bob", "Bob", "user"))
                    : userId == 5 ? Optional.of(new MetisUser(5, "admin", "Admin", "admin"))
                    : Optional.empty();
        }
    }

    private static final class FakeBattle extends BattleRepository {
        private FakeBattle() {
            super(null);
        }

        @Override
        public boolean canViewSession(long sessionId, MetisUser user) {
            return sessionId == 42;
        }

        @Override
        public boolean canEditSession(long sessionId, MetisUser user) {
            return user.admin();
        }
    }
}

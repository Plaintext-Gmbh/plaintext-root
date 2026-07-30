/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.config;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.sessions.service.HttpSessionRegistry;
import ch.plaintext.sessions.service.SessionAuditServiceImpl;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filter that tracks user sessions asynchronously for minimal performance impact.
 * Updates session audit trail on every request to keep track of active sessions.
 */
@Component
@Order(100)
@Slf4j
public class SessionTrackingFilter implements Filter {

    private final SessionAuditServiceImpl sessionAuditService;
    private final PlaintextSecurity security;
    private final HttpSessionRegistry sessionRegistry;

    public SessionTrackingFilter(SessionAuditServiceImpl sessionAuditService,
                                PlaintextSecurity security,
                                HttpSessionRegistry sessionRegistry) {
        this.sessionAuditService = sessionAuditService;
        this.security = security;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest) {
            // Track session asynchronously to avoid performance impact
            trackSessionAsync(httpRequest);
        }

        // Continue with the request
        chain.doFilter(request, response);
    }

    @Async
    public void trackSessionAsync(HttpServletRequest request) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication != null && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getPrincipal())) {

                HttpSession session = request.getSession(false);
                if (session != null) {
                    String sessionId = session.getId();
                    Long userId = security.getId();
                    String userAgent = request.getHeader("User-Agent");

                    if (userId != null && sessionId != null) {
                        // Register session in the registry for cross-session access
                        sessionRegistry.registerSession(sessionId, session);

                        sessionAuditService.updateOrCreate(userId, sessionId, authentication, userAgent);
                    }
                }
            }
        } catch (Exception e) {
            // Log but don't fail the request
            log.debug("Session tracking error (non-critical): {}", e.getMessage());
        }
    }
}

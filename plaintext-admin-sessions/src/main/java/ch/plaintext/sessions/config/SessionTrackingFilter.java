/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.config;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.sessions.service.HttpSessionRegistry;
import ch.plaintext.sessions.service.SessionAuditServiceImpl;
import ch.plaintext.sessions.service.SessionAuditWriter;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filter that records sessions. Writing to the database runs outside the
 * request thread ({@link SessionAuditWriter}); everything thread-bound is read here beforehand.
 * Updates session audit trail on every request to keep track of active sessions.
 *
 * <p>Card 627: the <em>recording</em> into {@code USER_SESSION} can be switched off per tenant
 * via Root&nbsp;→&nbsp;Setup. The transient registration in the
 * {@link HttpSessionRegistry} deliberately stays unaffected by that — it is the basis for
 * forcibly terminating a session, and is not a recording.</p>
 */
@Component
@Order(100)
@Slf4j
public class SessionTrackingFilter implements Filter {

    private final SessionAuditWriter sessionAuditWriter;
    private final PlaintextSecurity security;
    private final HttpSessionRegistry sessionRegistry;
    /**
     * Optional: the module {@code plaintext-admin-settings} is not wired into every application
     * (this module does not depend on it). Without the bean, recording happens as before card 627 —
     * a missing switch must not keep an application from starting, nor silently turn it off.
     */
    private final ObjectProvider<ISetupConfigService> setupConfigService;

    public SessionTrackingFilter(SessionAuditWriter sessionAuditWriter,
                                PlaintextSecurity security,
                                HttpSessionRegistry sessionRegistry,
                                ObjectProvider<ISetupConfigService> setupConfigService) {
        this.sessionAuditWriter = sessionAuditWriter;
        this.security = security;
        this.sessionRegistry = sessionRegistry;
        this.setupConfigService = setupConfigService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest) {
            sammleUndUebergib(httpRequest);
        }

        chain.doFilter(request, response);
    }

    /**
     * Reads everything thread- and request-bound <b>here</b>, on the request thread, and hands only
     * the database write on to {@link SessionAuditWriter}.
     *
     * <p><b>Card 968 (Sonar {@code java:S6809}).</b> Previously this method itself carried
     * {@code @Async} and was called via {@code this} — the self-invocation bypasses the Spring
     * proxy, the annotation had no effect, everything ran on the request thread. The comment
     * next to it claimed the opposite.
     *
     * <p>The obvious route — injecting the bean into itself — would have been <b>wrong</b>
     * here: {@code SecurityContextHolder}, {@code HttpServletRequest} and
     * {@link PlaintextSecurity} are bound to the request thread. On a pool thread the
     * login would no longer have been visible and the recording would have stopped silently. So
     * the self-invocation was load-bearing by accident; that is exactly what makes this finding
     * more dangerous than the 71 remaining S6809 sites, where the outer method already brings
     * the same wrapper along.
     *
     * <p>{@link #aufzeichnungAktiv()} is deliberately evaluated here as well: it queries
     * {@code security.getMandat()}.
     */
    private void sammleUndUebergib(HttpServletRequest request) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || "anonymousUser".equals(authentication.getPrincipal())) {
                return;
            }

            HttpSession session = request.getSession(false);
            if (session == null) {
                return;
            }
            String sessionId = session.getId();
            Long userId = security.getId();
            if (userId == null || sessionId == null) {
                return;
            }
            String userAgent = request.getHeader("User-Agent");

            // Stays synchronous: an entry in an in-memory map, and the basis for forcibly
            // terminating a session. It must not lag behind.
            sessionRegistry.registerSession(sessionId, session);

            // Card 627: exactly one evaluation of the switch, immediately before the single
            // write path - not spread across several call sites.
            if (aufzeichnungAktiv()) {
                sessionAuditWriter.schreibe(userId, sessionId, authentication, userAgent);
            }
        } catch (Exception e) {
            // Log but don't fail the request
            log.debug("Session tracking error (non-critical): {}", e.getMessage());
        }
    }

    /**
     * Card 627: switch from Root→Setup, read per request (flipping it takes effect at once, without
     * a restart). <b>true</b> when in doubt — a missing settings module, a missing configuration or
     * an error while reading must not silently end the recording; that would be a data loss nobody
     * notices.
     */
    private boolean aufzeichnungAktiv() {
        ISetupConfigService service = setupConfigService.getIfAvailable();
        if (service == null) {
            return true;
        }
        try {
            return service.isSessionTrackingEnabled(security.getMandat());
        } catch (Exception e) {
            log.debug("Session-Tracking-Schalter nicht lesbar, zeichne auf: {}", e.getMessage());
            return true;
        }
    }
}

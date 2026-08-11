/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.config;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.sessions.service.HttpSessionRegistry;
import ch.plaintext.sessions.service.SessionAuditServiceImpl;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filter that tracks user sessions asynchronously for minimal performance impact.
 * Updates session audit trail on every request to keep track of active sessions.
 *
 * <p>Karte 627: Das <em>Aufzeichnen</em> in {@code USER_SESSION} lässt sich über
 * Root&nbsp;→&nbsp;Setup je Mandant abschalten. Die flüchtige Registrierung in der
 * {@link HttpSessionRegistry} bleibt davon bewusst unberührt — sie ist die Grundlage dafür,
 * eine Sitzung zwangsweise zu beenden, und ist keine Aufzeichnung.</p>
 */
@Component
@Order(100)
@Slf4j
public class SessionTrackingFilter implements Filter {

    private final SessionAuditServiceImpl sessionAuditService;
    private final PlaintextSecurity security;
    private final HttpSessionRegistry sessionRegistry;
    /**
     * Optional: Das Modul {@code plaintext-admin-settings} ist nicht in jeder Anwendung eingebunden
     * (dieses Modul hängt nicht davon ab). Fehlt die Bean, wird aufgezeichnet wie vor Karte 627 —
     * ein fehlender Schalter darf keine Anwendung am Starten hindern und nichts still abschalten.
     */
    private final ObjectProvider<ISetupConfigService> setupConfigService;

    public SessionTrackingFilter(SessionAuditServiceImpl sessionAuditService,
                                PlaintextSecurity security,
                                HttpSessionRegistry sessionRegistry,
                                ObjectProvider<ISetupConfigService> setupConfigService) {
        this.sessionAuditService = sessionAuditService;
        this.security = security;
        this.sessionRegistry = sessionRegistry;
        this.setupConfigService = setupConfigService;
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

                        // Karte 627: genau eine Auswertung des Schalters, unmittelbar vor dem
                        // einzigen Schreibpfad — nicht auf mehrere Aufrufstellen verteilt.
                        if (aufzeichnungAktiv()) {
                            sessionAuditService.updateOrCreate(userId, sessionId, authentication, userAgent);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log but don't fail the request
            log.debug("Session tracking error (non-critical): {}", e.getMessage());
        }
    }

    /**
     * Karte 627: Schalter aus Root→Setup, je Request gelesen (Umlegen wirkt sofort, ohne Neustart).
     * Im Zweifel <b>true</b> — fehlendes Settings-Modul, fehlende Konfiguration oder ein Fehler beim
     * Lesen dürfen die Aufzeichnung nicht stillschweigend beenden; das wäre ein Datenverlust, den
     * niemand bemerkt.
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

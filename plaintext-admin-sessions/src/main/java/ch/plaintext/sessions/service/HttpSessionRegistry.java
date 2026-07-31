/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry that maintains references to all active HTTP sessions
 * This allows ROOT users to inspect session contents across all users
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Component
@Slf4j
public class HttpSessionRegistry {

    // Thread-safe map to store session references
    /**
     * Attributname, unter dem Spring Security den {@code SecurityContext} in der Session ablegt.
     * Bewusst als Konstante dupliziert, damit dieses Modul nicht von spring-security-web abhaengt.
     */
    private static final String SPRING_SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";

    private final Map<String, HttpSession> sessionMap = new ConcurrentHashMap<>();

    /**
     * Register a session in the registry
     * @param sessionId The session ID
     * @param session The HttpSession object
     */
    public void registerSession(String sessionId, HttpSession session) {
        sessionMap.put(sessionId, session);
        log.debug("Registered session: {}", sessionId);
    }

    /**
     * Remove a session from the registry
     * @param sessionId The session ID
     */
    public void unregisterSession(String sessionId) {
        sessionMap.remove(sessionId);
        log.debug("Unregistered session: {}", sessionId);
    }

    /**
     * Get a session by its ID
     * @param sessionId The session ID
     * @return Optional containing the session if found
     */
    public Optional<HttpSession> getSession(String sessionId) {
        return Optional.ofNullable(sessionMap.get(sessionId));
    }

    /**
     * Get all registered sessions
     * @return List of all session IDs
     */
    public List<String> getAllSessionIds() {
        return sessionMap.keySet().stream()
                .collect(Collectors.toList());
    }

    /**
     * Get count of active sessions
     * @return Number of active sessions
     */
    public int getActiveSessionCount() {
        return sessionMap.size();
    }

    /**
     * SECURITY (Karte 314, Punkt 9): invalidiert alle aktiven Sessions eines Benutzers.
     *
     * <p>Wird nach einem Passwort-Reset aufgerufen. Ohne das behaelt ein Angreifer, der bereits
     * eine Session auf dem Konto hat, seinen Zugriff ueber den Passwortwechsel hinaus — der
     * Reset waere dann als Wiederherstellungsmassnahme wirkungslos. Die persistenten
     * Remember-Me-Tokens werden separat geloescht.</p>
     *
     * <p>Gelesen wird der in der Session abgelegte {@code SecurityContext} von Springs
     * {@code HttpSessionSecurityContextRepository}. Fehler beim Invalidieren (z.B. eine bereits
     * abgelaufene Session) werden geschluckt, damit ein einzelner Fehlschlag die uebrigen
     * Sessions nicht stehen laesst.</p>
     *
     * @param username der Benutzername, dessen Sessions beendet werden sollen
     * @return Anzahl der invalidierten Sessions
     */
    public int invalidateSessionsOfUser(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        int invalidated = 0;
        for (Map.Entry<String, HttpSession> entry : sessionMap.entrySet()) {
            HttpSession session = entry.getValue();
            try {
                Object ctx = session.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
                if (!(ctx instanceof SecurityContext securityContext)) {
                    continue;
                }
                Authentication authentication = securityContext.getAuthentication();
                if (authentication == null || !username.equalsIgnoreCase(authentication.getName())) {
                    continue;
                }
                session.invalidate();
                invalidated++;
            } catch (RuntimeException e) {
                // Session bereits invalidiert/abgelaufen -> aus der Registry werfen und weitermachen.
                log.debug("Session {} konnte nicht invalidiert werden: {}", entry.getKey(), e.toString());
            } finally {
                sessionMap.remove(entry.getKey());
            }
        }
        if (invalidated > 0) {
            log.info("{} aktive Session(s) von '{}' nach Passwortwechsel invalidiert", invalidated, username);
        }
        return invalidated;
    }
}

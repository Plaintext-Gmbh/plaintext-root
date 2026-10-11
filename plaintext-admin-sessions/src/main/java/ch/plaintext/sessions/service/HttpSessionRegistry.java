/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.service;

import ch.plaintext.boot.plugins.log.Log;
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
     * Attribute name under which Spring Security stores the {@code SecurityContext} in the session.
     * Deliberately duplicated as a constant so this module does not depend on spring-security-web.
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
     * SECURITY (card 314, item 9): invalidates all active sessions of a user.
     *
     * <p>Called after a password reset. Without it an attacker who already has
     * a session on the account keeps their access beyond the password change — the
     * reset would then be ineffective as a recovery measure. The persistent
     * remember-me tokens are deleted separately.</p>
     *
     * <p>What is read is the {@code SecurityContext} stored in the session by Spring's
     * {@code HttpSessionSecurityContextRepository}. Errors while invalidating (e.g. an already
     * expired session) are swallowed so that a single failure does not leave the remaining
     * sessions standing.</p>
     *
     * @param username the user name whose sessions are to be terminated
     * @return number of invalidated sessions
     */
    public int invalidateSessionsOfUser(String username) {
        return invalidateSessionsOfUser(username, null);
    }

    /**
     * Like {@link #invalidateSessionsOfUser(String)}, but leaves the session with the id
     * {@code exceptSessionId} alive (card 1495, S-5): whoever changes their own password or turns on
     * 2FA stays logged in in the session they did it from; every other session of the account ends.
     *
     * @param username        the user name whose sessions are to be terminated
     * @param exceptSessionId id of the session to keep, or {@code null}
     * @return number of invalidated sessions
     */
    public int invalidateSessionsOfUser(String username, String exceptSessionId) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        int invalidated = 0;
        for (Map.Entry<String, HttpSession> entry : sessionMap.entrySet()) {
            HttpSession session = entry.getValue();
            if (entry.getKey().equals(exceptSessionId)) {
                continue;
            }
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
                sessionMap.remove(entry.getKey());
            } catch (RuntimeException e) {
                // Session already invalidated/expired -> drop it from the registry and carry on.
                log.debug("Session {} konnte nicht invalidiert werden: {}", entry.getKey(), e.toString());
                sessionMap.remove(entry.getKey());
            }
            // Card 1495: sessions of OTHER users stay registered. The former `finally` removed every
            // visited entry, so one password change emptied the registry and a later invalidation
            // (or the ROOT session view) no longer saw anybody's sessions.
        }
        if (invalidated > 0) {
            log.info("{} aktive Session(s) von '{}' nach Passwortwechsel invalidiert", invalidated, Log.mail(username));
        }
        return invalidated;
    }
}

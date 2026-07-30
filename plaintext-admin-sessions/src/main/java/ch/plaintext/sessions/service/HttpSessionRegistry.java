/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.service;

import jakarta.servlet.http.HttpSession;
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
}

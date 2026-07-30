/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.service;

import ch.plaintext.sessions.ISessionAuditService;
import ch.plaintext.sessions.entity.UserSession;
import ch.plaintext.sessions.repository.UserSessionRepository;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Named("sessionAuditService")
@Slf4j
public class SessionAuditServiceImpl implements ISessionAuditService {

    private final UserSessionRepository repository;

    public SessionAuditServiceImpl(UserSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void updateOrCreate(Long userId, String sessionId, Authentication authentication, String userAgent) {
        if (userId == null || sessionId == null) {
            log.warn("updateOrCreate called with null userId or sessionId");
            return;
        }

        try {
            String username = authentication != null ? authentication.getName() : "unknown";
            String mandat = "unknown";
            if (authentication != null && authentication.getAuthorities() != null) {
                mandat = authentication.getAuthorities().stream()
                    .map(auth -> auth.getAuthority())
                    .filter(auth -> auth.startsWith("PROPERTY_MANDAT_"))
                    .map(auth -> auth.substring("PROPERTY_MANDAT_".length()))
                    .findFirst()
                    .orElse("unknown");
            }

            repository.upsertSession(userId, username, sessionId, mandat,
                userAgent != null ? userAgent : "", LocalDateTime.now());

            log.debug("Session updated/created: userId={}, sessionId={}", userId, sessionId);
        } catch (Exception e) {
            log.error("Error updating/creating session: userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    @Override
    @Transactional
    public void commit(Long userId) {
        if (userId == null) {
            log.warn("commit called with null userId");
            return;
        }

        try {
            List<UserSession> activeSessions = repository.findByUserIdAndActive(userId, true);
            for (UserSession session : activeSessions) {
                session.setActive(false);
                repository.save(session);
            }
            log.debug("Marked {} sessions as inactive for userId={}", activeSessions.size(), userId);
        } catch (Exception e) {
            log.error("Error committing sessions for userId={}", userId, e);
        }
    }

    public List<UserSession> getAllActiveSessions() {
        try {
            return repository.findAllByActive(true);
        } catch (Exception e) {
            log.error("Error getting all active sessions", e);
            return List.of();
        }
    }

    public List<UserSession> getActiveSessionsByMandat(String mandat) {
        if (mandat == null || mandat.trim().isEmpty()) {
            log.warn("getActiveSessionsByMandat called with null or empty mandat");
            return List.of();
        }

        try {
            return repository.findByMandatAndActive(mandat, true);
        } catch (Exception e) {
            log.error("Error getting active sessions for mandat={}", mandat, e);
            return List.of();
        }
    }

    @Transactional
    public void forceLogout(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            log.warn("forceLogout called with null or empty sessionId");
            return;
        }

        try {
            List<UserSession> sessions = repository.findBySessionId(sessionId);
            if (!sessions.isEmpty()) {
                for (UserSession userSession : sessions) {
                    userSession.setActive(false);
                    repository.save(userSession);
                    log.info("Forced logout for sessionId={}, username={}", sessionId, userSession.getUsername());
                }
                if (sessions.size() > 1) {
                    log.warn("⚠️ Found and logged out {} duplicate sessions for sessionId={}", sessions.size(), sessionId);
                }
            } else {
                log.warn("Session not found for sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.error("Error forcing logout for sessionId={}", sessionId, e);
        }
    }
}

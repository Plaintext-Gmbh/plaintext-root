/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Writes the session audit entry <b>outside</b> the request thread.
 *
 * <p><b>Why a bean of its own (card 968, Sonar {@code java:S6809}).</b> In the
 * {@code SessionTrackingFilter}, {@code @Async} sat on a method that the filter called via
 * {@code this}. A self-invocation bypasses the Spring proxy — the annotation had no
 * effect, the write ran on the request thread. The comment next to it claimed the
 * opposite ("asynchronously to avoid performance impact").
 *
 * <p><b>Why this could not simply be cured by injecting the bean into itself.</b> The old method
 * read three things bound to the thread or to the request: {@code SecurityContextHolder}, the
 * {@code HttpServletRequest} and {@link ch.plaintext.PlaintextSecurity}. Had it really
 * run asynchronously, the {@code SecurityContextHolder} would have been empty on the pool thread —
 * the recording would have <i>stopped entirely and silently</i>. So the self-invocation was
 * load-bearing by accident. That is why everything thread-bound is read in the filter and only the
 * database write is handed over here; {@link SessionAuditServiceImpl#updateOrCreate} takes
 * only parameters anyway and reads no thread state.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionAuditWriter {

    private final SessionAuditServiceImpl sessionAuditService;

    /**
     * @param authentication collected on the request thread — the
     *                       {@code SecurityContextHolder} is deliberately NOT queried here
     */
    @Async
    public void schreibe(Long userId, String sessionId, Authentication authentication, String userAgent) {
        try {
            sessionAuditService.updateOrCreate(userId, sessionId, authentication, userAgent);
        } catch (Exception e) {
            // A failed recording must not affect the request - by this point it has long since
            // been answered anyway.
            log.debug("Session tracking error (non-critical): {}", e.getMessage());
        }
    }
}

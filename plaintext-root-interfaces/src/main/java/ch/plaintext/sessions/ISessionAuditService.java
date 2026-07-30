/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions;

import org.springframework.security.core.Authentication;

/**
 * Service interface for auditing user sessions.
 * Tracks user login sessions including authentication details and user agent information.
 */
public interface ISessionAuditService {

    /**
     * Creates a new session audit entry or updates an existing one.
     *
     * @param userId         the ID of the user
     * @param sessionId      the HTTP session ID
     * @param authentication the Spring Security authentication object
     * @param userAgent      the User-Agent header from the HTTP request
     */
    void updateOrCreate(Long userId, String sessionId, Authentication authentication, String userAgent);

    /**
     * Commits (finalizes) the session audit record for a user, typically on logout.
     *
     * @param userId the ID of the user whose session is being committed
     */
    void commit(Long userId);
}

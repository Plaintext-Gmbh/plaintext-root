/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.impersonation;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One impersonation event: which admin impersonated which user and when, and when (if it has
 * already happened) it was ended again. A persistent, queryable source for ROOT impersonation —
 * before this there was only {@code log.info} for it.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Entity
@Data
public class ImpersonationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long adminUserId;
    private String adminUsername;
    private Long targetUserId;
    private String targetUsername;
    private LocalDateTime startedAt;
    /** {@code null} as long as the impersonation is still active. */
    private LocalDateTime endedAt;
    /** HTTP session ID in which the impersonation took place (best effort, may be null). */
    private String sessionId;
}

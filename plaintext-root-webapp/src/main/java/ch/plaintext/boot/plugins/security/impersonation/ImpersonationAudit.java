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
 * Ein Impersonation-Vorgang: welcher Admin hat wann welchen User impersoniert, und wann (falls
 * bereits geschehen) wieder beendet. Persistente, abfragbare Quelle für ROOT-Impersonation —
 * vorher gab es dafür nur {@code log.info}.
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
    /** {@code null}, solange die Impersonation noch aktiv ist. */
    private LocalDateTime endedAt;
    /** HTTP-Session-ID, in der impersoniert wurde (best effort, kann null sein). */
    private String sessionId;
}

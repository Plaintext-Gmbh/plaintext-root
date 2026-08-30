/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.audit;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Generic audit log for destructive operations (who/when/what) — usable by every app
 * (root/app/guild/iot/schuetu) through {@link DestructiveActionAuditService}. "Who"/"when" come
 * from the {@link SuperModel} standard columns ({@code createdBy}/{@code createdDate}); so does
 * {@code mandat}.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Entity
@Table(name = "destructive_action_audit")
@Data
@EqualsAndHashCode(callSuper = false)
public class DestructiveActionAudit extends SuperModel {

    /** {@code UI} or {@code MCP}. */
    @Column(name = "channel", length = 20)
    private String channel;

    /** Freely chosen action identifier, e.g. {@code RECHNUNG_HARD_DELETE}. */
    @Column(name = "action_type", length = 100)
    private String actionType;

    /** Affected entity type, e.g. {@code Rechnung}. */
    @Column(name = "entity_type", length = 100)
    private String entityType;

    /** Affected IDs (e.g. comma separated) or a count as text. */
    @Column(name = "entity_ids", length = 2000)
    private String entityIds;

    /** Free text, e.g. confirmation phrase, error message, context. */
    @Column(name = "detail", length = 2000)
    private String detail;
}

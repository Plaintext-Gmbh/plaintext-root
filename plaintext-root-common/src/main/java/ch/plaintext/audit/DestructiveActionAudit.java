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
 * Generisches Audit-Log für destruktive Operationen (wer/wann/was) — nutzbar von jeder App
 * (root/app/guild/iot/schuetu) über {@link DestructiveActionAuditService}. „Wer"/„wann" kommen über
 * die {@link SuperModel}-Standardspalten ({@code createdBy}/{@code createdDate}); {@code mandat}
 * ebenso.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Entity
@Table(name = "destructive_action_audit")
@Data
@EqualsAndHashCode(callSuper = false)
public class DestructiveActionAudit extends SuperModel {

    /** {@code UI} oder {@code MCP}. */
    @Column(name = "channel", length = 20)
    private String channel;

    /** Frei wählbarer Aktions-Bezeichner, z. B. {@code RECHNUNG_HARD_DELETE}. */
    @Column(name = "action_type", length = 100)
    private String actionType;

    /** Betroffener Entity-Typ, z. B. {@code Rechnung}. */
    @Column(name = "entity_type", length = 100)
    private String entityType;

    /** Betroffene IDs (z. B. kommagetrennt) oder eine Anzahl als Text. */
    @Column(name = "entity_ids", length = 2000)
    private String entityIds;

    /** Freitext, z. B. Bestätigungsphrase, Fehlermeldung, Kontext. */
    @Column(name = "detail", length = 2000)
    private String detail;
}

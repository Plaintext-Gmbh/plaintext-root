/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.entity;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Eine In-App-Benachrichtigung fuer einen Benutzer (mandantengescoped ueber {@link SuperModel}).
 * {@code gelesenAm == null} bedeutet ungelesen.
 */
@Entity
@Table(name = "notification")
@Data
@EqualsAndHashCode(callSuper = false)
public class Notification extends SuperModel {

    @Column(name = "empfaenger_username", length = 255, nullable = false)
    private String empfaengerUsername;

    /** Benachrichtigungstyp, z. B. {@code auszahlungen.neu} — entspricht dem Mailtext-Key {@code notif.<typ>}. */
    @Column(name = "typ", length = 200, nullable = false)
    private String typ;

    @Column(name = "titel", length = 500, nullable = false)
    private String titel;

    @Column(name = "text", length = 2000, nullable = false)
    private String text;

    /** Optionale Ziel-URL in der App (relativ), oder {@code null}. */
    @Column(name = "link", length = 500)
    private String link;

    /** {@code null} = ungelesen. */
    @Column(name = "gelesen_am")
    private LocalDateTime gelesenAm;

    @Column(name = "quelle_entity_type", length = 200)
    private String quelleEntityType;

    @Column(name = "quelle_entity_id", length = 100)
    private String quelleEntityId;
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link.entity;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Ein Freigabe-Link (Karte 1476). Gespeichert ist nur der SHA-256 des Tokens; das Token sieht nur, wer den Link
 * anlegt. Widerrufen = {@code deleted}. Der Teil steht hier und nicht in der Adresse, damit ihn der Empfänger nicht
 * ändern kann.
 */
@Data
@Entity
@Table(name = "freigabe_link")
@EqualsAndHashCode(callSuper = true)
public class FreigabeLink extends SuperModel {

    @Column(length = 40, nullable = false)
    private String typ;

    @Column(nullable = false)
    private Long objektId;

    @Column(length = 200)
    private String teil;

    /** {@code r} oder {@code rw}. */
    @Column(length = 2, nullable = false)
    private String recht;

    /** SHA-256 des Tokens als Hex. */
    @Column(length = 64, nullable = false, unique = true)
    private String tokenHash;

    @Column(length = 500)
    private String zweck;

    /** Letzter gültiger Tag; {@code null} = unbefristet. */
    private LocalDate gueltigBis;

    @Column(nullable = false)
    private int aufrufe;

    private Instant zuletztAufgerufen;
}

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
 *
 * <p>Seit Karte 1559 trägt dieselbe Tabelle auch die Links des {@link ch.plaintext.linktoken.LinkTokenStore}
 * (Draw.io, Diashows, Files): bei ihnen steht der Objektschlüssel als Text in {@link #schluessel}, {@link #objektId}
 * bleibt leer. {@code FreigabeLinkService} öffnet nur Zeilen ohne Schlüssel, der Store nur Zeilen mit.</p>
 */
@Data
@Entity
@Table(name = "freigabe_link")
@EqualsAndHashCode(callSuper = true)
public class FreigabeLink extends SuperModel {

    @Column(length = 40, nullable = false)
    private String typ;

    /** Objekt eines Freigabe-Links (Karte 1476); {@code null} bei einem LinkTokenStore-Link. */
    private Long objektId;

    /** Objektschlüssel eines LinkTokenStore-Links (Karte 1559), z.B. ein Dateipfad; {@code null} bei Freigabe-Links. */
    @Column(length = 1000)
    private String schluessel;

    /** Wer den LinkTokenStore-Link angelegt hat (Karte 1559). */
    @Column(length = 255)
    private String besitzer;

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

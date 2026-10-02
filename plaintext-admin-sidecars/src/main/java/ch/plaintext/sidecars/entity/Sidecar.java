/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.entity;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.Instant;

/**
 * Ein bekannter Sidecar mit dem letzten Stand seiner Protokoll-Abfrage (Karte 1400).
 *
 * <p>Instanzweit ({@code mandat = system}): ein Sidecar ist ein Container des Hosts. Der Token liegt
 * verschlüsselt ({@code SidecarCrypto}) und wird nie angezeigt; {@link ToString} lässt ihn aus.</p>
 */
@Entity
@Table(name = "sidecar")
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = {"tokenEncrypted", "beschreibungJson"})
public class Sidecar extends SuperModel {

    /** Mandat aller Sidecar-Einträge. */
    public static final String MANDAT = "system";

    @Column(name = "name", length = 40, nullable = false)
    private String name;

    @Column(name = "url", length = 500, nullable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "quelle", length = 16, nullable = false)
    private SidecarQuelle quelle = SidecarQuelle.HAND;

    @Column(name = "token_encrypted", length = 2000)
    private String tokenEncrypted;

    @Column(name = "erreichbar", nullable = false)
    private boolean erreichbar;

    @Column(name = "protokoll", length = 40)
    private String protokoll;

    @Column(name = "titel", length = 200)
    private String titel;

    @Column(name = "version", length = 80)
    private String version;

    /** {@code ok}, {@code eingeschraenkt}, {@code fehler} oder {@code null} (unbekannt) */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "status_text", length = 500)
    private String statusText;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_zustand", length = 20)
    private AuthZustand authZustand = AuthZustand.UNBEKANNT;

    @Column(name = "antwort_ms")
    private Integer antwortMs;

    @Column(name = "letzte_abfrage")
    private Instant letzteAbfrage;

    @Column(name = "fehler", length = 500)
    private String fehler;

    /** die zuletzt gelieferte Beschreibung, unverändert (für MCP und die Fähigkeiten-Liste) */
    @Column(name = "beschreibung_json", columnDefinition = "text")
    private String beschreibungJson;

    public boolean hatToken() {
        return tokenEncrypted != null && !tokenEncrypted.isEmpty();
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.entity;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.Instant;

/**
 * Eine Speicher-Ablage (Karte 1406): Server, Zugang und Pfad, unter dem Module Dateien ablegen.
 * Nextcloud (WebDAV) oder Git (Karte 1471). Instanzweit wie {@link Sidecar}; das App-Passwort liegt verschlüsselt
 * und wird nie angezeigt.
 */
@Entity
@Table(name = "speicher_ablage")
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = "passwortEncrypted")
public class SpeicherAblage extends SuperModel {

    /** Art der Ablage: Nextcloud über WebDAV. */
    public static final String ART_NEXTCLOUD = "NEXTCLOUD";

    /** Art der Ablage: Git-Repository, Schreiben = Commit + Push (Karte 1471). */
    public static final String ART_GIT = "GIT";

    @Column(name = "name", length = 40, nullable = false)
    private String name;

    @Column(name = "art", length = 20, nullable = false)
    private String art = ART_NEXTCLOUD;

    /** Nextcloud-Adresse, z. B. https://home.plaintext.ch (oder direkt die WebDAV-URL), bzw. die https-Adresse des Repos */
    @Column(name = "url", length = 500, nullable = false)
    private String url;

    @Column(name = "benutzer", length = 200, nullable = false)
    private String benutzer;

    @Column(name = "passwort_encrypted", length = 2000)
    private String passwortEncrypted;

    /** Ordner innerhalb der Nextcloud bzw. des Repos, z. B. Projekte/drawio (bei Git leer = Wurzel) */
    @Column(name = "pfad", length = 500, nullable = false)
    private String pfad;

    /** Nur bei {@link #ART_GIT}: der Zweig, z. B. main */
    @Column(name = "zweig", length = 200)
    private String zweig;

    @Column(name = "ok")
    private Boolean ok;

    @Column(name = "meldung", length = 500)
    private String meldung;

    @Column(name = "letzte_pruefung")
    private Instant letztePruefung;

    public boolean hatPasswort() {
        return passwortEncrypted != null && !passwortEncrypted.isEmpty();
    }
}

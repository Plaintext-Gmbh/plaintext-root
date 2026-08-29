/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.model;

import ch.plaintext.boot.plugins.security.helpers.MyUserSetConverter;
import ch.plaintext.boot.plugins.security.helpers.RecoveryCodesConverter;
import ch.plaintext.boot.plugins.security.helpers.TotpSecretConverter;
import jakarta.persistence.*;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Entity
@Data
public class MyUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String password = "";
    private String startpage = "";

    @Column(name = "OIDC_SUBJECT")
    private String oidcSubject;

    private boolean passwordless;

    /**
     * Erzwingt einen Passwortwechsel beim naechsten Login (Karte 306). Wird fuer den
     * Root-Bootstrap-User gesetzt (Einmal-Initialpasswort statt statischem {@code root}) und
     * nach erfolgreichem Selbst-Passwortwechsel wieder geloescht. Default {@code false}, sodass
     * Bestands-User nicht betroffen sind.
     */
    @Column(name = "MUST_CHANGE_PASSWORD")
    private boolean mustChangePassword;

    /**
     * Base32-kodiertes TOTP-Secret (RFC 6238). {@code null}, solange 2FA nicht
     * eingerichtet ist. Das Secret ist der geteilte Schluessel zwischen Server und
     * Authenticator-App; es wird ausschliesslich lokal geprueft und nie ueber die
     * Login-Grenze hinweg weitergegeben.
     */
    @Column(name = "TOTP_SECRET", length = 255)
    @Convert(converter = TotpSecretConverter.class)
    private String totpSecret;

    /**
     * Ob der zweite Faktor fuer diesen (lokalen) User scharf ist. Erst {@code true},
     * nachdem der User bei der Einrichtung einen gueltigen Code bestaetigt hat
     * (kein Aussperren durch versehentliches Aktivieren). Default {@code false}.
     */
    @Column(name = "TOTP_ENABLED")
    private boolean totpEnabled;

    /**
     * Gehashte (SHA-256, Hex) Einmal-Recovery-Codes. Der Klartext wird dem User
     * genau einmal bei der Einrichtung angezeigt und danach nur noch gehasht
     * gespeichert – so kann ein legitimer User sich trotz verlorenem Authenticator
     * nie dauerhaft aussperren, ohne dass ein DB-Leak die Codes preisgibt.
     */
    @Convert(converter = RecoveryCodesConverter.class)
    @Column(name = "RECOVERY_CODES", length = 2000)
    private Set<String> recoveryCodes = new HashSet<>();

    @Convert(converter = MyUserSetConverter.class)
    private Set<String> roles = new HashSet<>();

    /**
     * Vor- und Nachname, beide optional.
     *
     * <p><b>Warum sie neu sind (Auftrag Daniel, 25.08.2026: „noch Vor- und Nachname einblenden").</b>
     * Am Benutzer gab es sie bis hierher <b>nicht</b> — weder als Feld noch als Spalte in
     * {@code my_user_entity}. Das Rahmenwerk erwartet sie allerdings seit jeher:
     * {@code ch.plaintext.framework.PlaintextUser} deklariert {@code getVorname()} und
     * {@code getNachname()}. Nur hatte diese Schnittstelle <b>keine einzige Implementierung</b>,
     * die Erwartung lief also ins Leere.
     *
     * <p>Sie bleiben bewusst {@code null}-fähig: bestehende Konten haben keine Namen, und ein
     * Pflichtfeld wuerde jedes Speichern eines Altkontos blockieren.
     */
    private String vorname;

    private String nachname;

    /** Vor- und Nachname zusammen, oder leer — fuer Anzeige und Sortierung in einer Spalte. */
    @Transient
    public String getAnzeigename() {
        String v = vorname == null ? "" : vorname.trim();
        String n = nachname == null ? "" : nachname.trim();
        return (v + " " + n).trim();
    }

    public void addRole(String role) {
        roles.add(role);
    }

    public void removeRole(String role) {
        roles.remove(role);
    }

    @Transient
    public String getMandat() {
        if (roles == null) {
            return null;
        }
        for (String role : roles) {
            if (role != null && role.toUpperCase().startsWith("PROPERTY_MANDAT_")) {
                return role.substring("PROPERTY_MANDAT_".length()).toLowerCase();
            }
        }
        return null;
    }

    public void setMandat(String mandat) {
        if (roles == null) {
            roles = new HashSet<>();
        }
        // Remove any existing mandat role
        roles.removeIf(role -> role != null && role.toUpperCase().startsWith("PROPERTY_MANDAT_"));

        // Add new mandat role if mandat is not null or empty
        if (mandat != null && !mandat.trim().isEmpty()) {
            roles.add("PROPERTY_MANDAT_" + mandat.toUpperCase());
        }
    }

}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.totp;

import org.springframework.security.core.Authentication;

import java.io.Serializable;

/**
 * Traeger fuer den "Passwort ok, zweiter Faktor ausstehend"-Zwischenzustand. Wird nach
 * erfolgreichem Passwort-Login in der HTTP-Session abgelegt (NICHT im SecurityContext) und
 * erst nach gueltigem TOTP-/Recovery-Code in eine volle Authentication ueberfuehrt.
 *
 * <p><b>Sicherheits-Invariante:</b> Solange dieses Objekt in der Session liegt und die
 * eigentliche Authentication NICHT im SecurityContext steht, gilt der Request-Kontext als
 * anonym – jeder Zugriff auf geschuetzte Ressourcen wird abgewiesen. Damit ist der zweite
 * Faktor nicht umgehbar.
 *
 * @param authentication die zurueckgehaltene, voll aufgeloeste Authentication
 * @param username       Login-Name (fuer Verifikation/Rate-Limit)
 * @param targetUrl      urspruenglich angepeiltes Ziel (Startseite), auf das nach Erfolg
 *                       weitergeleitet wird
 */
public record TotpPendingAuthentication(Authentication authentication, String username, String targetUrl)
        implements Serializable {

    /** Session-Attribut-Schluessel fuer den ausstehenden Zwei-Faktor-Zustand. */
    public static final String SESSION_ATTRIBUTE = "PLAINTEXT_TOTP_PENDING_AUTH";
}

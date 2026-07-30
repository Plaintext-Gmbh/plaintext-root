/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.List;

/**
 * Versendet System-/Auth-Mails (Passwort-Reset, Login-Link/Magic-Link, Registrierungs-Bestätigung) über ein
 * <b>GLOBAL</b>-Systemmailkonto der Mailbox (SMTP). Das Interface liegt in {@code plaintext-root-interfaces};
 * die <b>Implementierung liefert die App</b> ({@code plaintext-z-mailbox}). Root konsumiert sie optional
 * (z. B. {@code @Autowired(required=false)} / {@link org.springframework.beans.factory.ObjectProvider}),
 * damit root auch ohne die App baut/testet.
 *
 * <p>Löst die frühere, {@code configName}-basierte Root-Mail-Infrastruktur ({@code plaintext-root-email})
 * für Auth-Mails ab. Welches GLOBAL-Konto verwendet wird, wird in der Root-Konfiguration (Setup) gewählt
 * und in {@code SetupConfig#systemMailAccountId} abgelegt.</p>
 */
public interface SystemMailSender {

    /**
     * Die verfügbaren GLOBAL-Systemmailkonten (Scope {@code GLOBAL}, nur ROOT) – für die Auswahl in der
     * Root-Konfiguration. Leere Liste, wenn keines angelegt ist (dann Hinweis anzeigen).
     */
    List<SystemMailAccount> listGlobalAccounts();

    /**
     * Versendet eine System-Mail über das angegebene GLOBAL-Konto.
     *
     * @param accountId Id des GLOBAL-Systemmailkontos
     * @param to        Empfänger-Adresse
     * @param subject   Betreff
     * @param body      Nachrichtentext
     * @param html      {@code true} = HTML-Body, sonst Text
     * @return {@code true} bei erfolgreichem Versand; {@code false}, wenn das Konto fehlt, kein
     * GLOBAL-Konto ist oder der Versand nicht möglich war (nie eine Exception nach aussen)
     */
    boolean sendSystemMail(Long accountId, String to, String subject, String body, boolean html);
}

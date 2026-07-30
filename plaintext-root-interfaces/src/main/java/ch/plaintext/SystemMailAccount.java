/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

/**
 * Schlanke, modulübergreifende Referenz auf ein GLOBAL-Systemmailkonto der Mailbox – für die Auswahl in
 * der Root-Konfiguration (Setup), ohne die internen Mailbox-Typen offenzulegen.
 *
 * @param id           Id des Mailbox-Kontos
 * @param name         Anzeigename
 * @param emailAddress Absender-Adresse
 */
public record SystemMailAccount(Long id, String name, String emailAddress) {
}

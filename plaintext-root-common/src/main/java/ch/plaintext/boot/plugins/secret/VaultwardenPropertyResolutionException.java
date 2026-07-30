/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import lombok.Getter;

/**
 * Fail-fast-Fehler, wenn ein {@code vault:}-Property-Wert nicht aufgeloest werden
 * kann (Vault deaktiviert, Login-Fehler oder Item/Feld fehlt).
 *
 * <p><b>Sicherheit:</b> Die Meldung nennt ausschliesslich den <i>Property-Namen</i>
 * und den <i>Item-Namen</i> (plus einen generischen Grund) — NIEMALS einen
 * Secret-Wert, das Master-Passwort oder sonstige Tresor-Inhalte.</p>
 */
@Getter
public class VaultwardenPropertyResolutionException extends RuntimeException {

    /** Name des Properties, dessen Wert nicht aufgeloest werden konnte. */
    private final String propertyName;

    /** Name des referenzierten Vault-Items (kein Secret-Wert). */
    private final String itemName;

    /**
     * @param propertyName Property-Schluessel (z.B. {@code plaintext.buchhaltung.paperless-token})
     * @param itemName     referenzierter Item-Name (z.B. {@code guild.paperless-token})
     * @param reason       generischer Grund OHNE jeglichen Secret-Inhalt
     */
    public VaultwardenPropertyResolutionException(String propertyName, String itemName, String reason) {
        super("Vault-Property '" + propertyName + "' (Item '" + itemName
                + "') konnte nicht aufgeloest werden: " + reason);
        this.propertyName = propertyName;
        this.itemName = itemName;
    }
}

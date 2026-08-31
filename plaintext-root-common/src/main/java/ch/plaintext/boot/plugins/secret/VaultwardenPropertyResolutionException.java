/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import lombok.Getter;

/**
 * Fail-fast error raised when a {@code vault:} property value cannot be resolved
 * (vault disabled, login error or item/field missing).
 *
 * <p><b>Security:</b> the message names only the <i>property name</i> and the
 * <i>item name</i> (plus a generic reason) — NEVER a secret value, the master
 * password or any other vault content.</p>
 */
@Getter
public class VaultwardenPropertyResolutionException extends RuntimeException {

    /** Name of the property whose value could not be resolved. */
    private final String propertyName;

    /** Name of the referenced vault item (not a secret value). */
    private final String itemName;

    /**
     * @param propertyName property key (e.g. {@code plaintext.buchhaltung.paperless-token})
     * @param itemName     referenced item name (e.g. {@code guild.paperless-token})
     * @param reason       generic reason WITHOUT any secret content
     */
    public VaultwardenPropertyResolutionException(String propertyName, String itemName, String reason) {
        super("Vault-Property '" + propertyName + "' (Item '" + itemName
                + "') konnte nicht aufgeloest werden: " + reason);
        this.propertyName = propertyName;
        this.itemName = itemName;
    }
}

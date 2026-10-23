/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.linktoken;

import ch.plaintext.modules.ModulApi;

/**
 * Right of a token link (card 1559): {@code lesen} shows the object, {@code schreiben} also allows saving it
 * (e.g. the embedded Draw.io editor), without login and only for this one object.
 */
@ModulApi(art = ModulApi.Art.DTO)
public enum LinkRecht {
    LESEN("lesen"),
    SCHREIBEN("schreiben");

    private final String wert;

    LinkRecht(String wert) {
        this.wert = wert;
    }

    /** @return {@code lesen} or {@code schreiben}, as stored and used by MCP */
    public String wert() {
        return wert;
    }

    /** @return the right for a stored value; empty, unknown or {@code null} = {@link #LESEN} (old rows had none) */
    public static LinkRecht von(String wert) {
        String w = wert == null ? "" : wert.strip();
        return SCHREIBEN.wert.equalsIgnoreCase(w) || "rw".equalsIgnoreCase(w) ? SCHREIBEN : LESEN;
    }
}

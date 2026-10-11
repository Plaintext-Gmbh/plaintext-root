/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe;

import ch.plaintext.modules.ModulApi;

/** Right of a share link (card 1476): read only ({@code r}) or read and write ({@code rw}). */
@ModulApi(art = ModulApi.Art.DTO)
public enum FreigabeRecht {
    LESEN("r"),
    SCHREIBEN("rw");

    private final String kurz;

    FreigabeRecht(String kurz) {
        this.kurz = kurz;
    }

    /** @return {@code r} or {@code rw}, as stored in the database and used by MCP */
    public String kurz() {
        return kurz;
    }

    /** @throws IllegalArgumentException if the value is neither {@code r} nor {@code rw} */
    public static FreigabeRecht von(String kurz) {
        for (FreigabeRecht r : values()) {
            if (r.kurz.equalsIgnoreCase(kurz == null ? "" : kurz.strip())) {
                return r;
            }
        }
        throw new IllegalArgumentException("Recht muss r oder rw sein, war " + kurz);
    }
}

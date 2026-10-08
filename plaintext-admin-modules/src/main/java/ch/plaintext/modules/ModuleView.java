/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import ch.plaintext.modules.katalog.SchnittstellenKatalog;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * UI row of the module list: id, display name, version, on/off state and — card 1438 — the jar of
 * the module, through which the module page finds what it offers, implements and uses.
 */
@Data
@AllArgsConstructor
public class ModuleView {
    private String moduleId;
    private String displayName;
    private String version;
    private boolean enabled;
    /** Jar name without version, e.g. {@code plaintext-z-fotos}, or {@code ?}. */
    private String jar;

    /** @return root or modul (card 1438) */
    public String getEbene() {
        return SchnittstellenKatalog.ebene(jar);
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.diagnose;

import java.util.List;

/**
 * One row of the menu diagnostics: a menu item, the four filters from
 * {@code MenuItemImpl.isOn()} individually, and the concrete reason behind each no.
 *
 * @param titel        full menu title ({@code "Parent | Titel"})
 * @param link         menu link, e.g. {@code mandatemenu.html}
 * @param modulKeys    the module keys of the menu item (its own {@code moduleId}, those of the
 *                     parent menus, the menu root id)
 * @param rolleOk      filter 1: annotation roles
 * @param rolleGrund   reason when filter 1 says no (empty otherwise)
 * @param modulRolleOk filter 2: configured module roles
 * @param modulRolleGrund reason when filter 2 says no (empty otherwise)
 * @param modulOk      filter 3: module activated
 * @param modulGrund   reason when filter 3 says no (empty otherwise)
 * @param mandantOk    filter 4: tenant whitelist/blacklist
 * @param mandantGrund reason when filter 4 says no, otherwise a note (e.g. the root exemption)
 * @param sichtbar     overall result — the AND combination of all four filters
 * @author info@plaintext.ch
 * @since 1.608.0
 */
public record MenuDiagnoseZeile(
        String titel,
        String link,
        List<String> modulKeys,
        boolean rolleOk,
        String rolleGrund,
        boolean modulRolleOk,
        String modulRolleGrund,
        boolean modulOk,
        String modulGrund,
        boolean mandantOk,
        String mandantGrund,
        boolean sichtbar) {

    // Order from Daniel, 29.08.2026: the two derived values are deliberately NOT named getXxx().
    // This class is a record, and the RecordELResolver (Jakarta EL 6) resolves #{z.modulKeysText}
    // exclusively through a parameterless method NAMED modulKeysText() — it does not know about
    // bean getters. With getModulKeysText() the diagnostics page blew up while rendering with a
    // PropertyNotFoundException and stayed empty (guild PROD, 29.08.2026).
    // MenuDiagnoseZeileElTest pins this promise down.

    /**
     * The module keys as text for the table.
     *
     * @return comma-separated module keys, or {@code "—"} when none are known
     */
    public String modulKeysText() {
        return modulKeys == null || modulKeys.isEmpty() ? "—" : String.join(", ", modulKeys);
    }

    /**
     * The first filter that says no — the answer to "why don't I see this?".
     *
     * @return plain-text reason, or {@code ""} when the menu item is visible
     */
    public String ersterGrund() {
        if (!rolleOk) {
            return rolleGrund;
        }
        if (!modulRolleOk) {
            return modulRolleGrund;
        }
        if (!modulOk) {
            return modulGrund;
        }
        if (!mandantOk) {
            return mandantGrund;
        }
        return "";
    }
}

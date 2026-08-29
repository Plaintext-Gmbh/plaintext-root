/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.diagnose;

import java.util.List;

/**
 * Eine Zeile der Menue-Diagnose: ein Menuepunkt, die vier Filter aus
 * {@code MenuItemImpl.isOn()} einzeln, und zu jedem Nein der konkrete Grund.
 *
 * @param titel        voller Menue-Titel ({@code "Parent | Titel"})
 * @param link         Menue-Link, z.B. {@code mandatemenu.html}
 * @param modulKeys    die Modul-Keys des Menuepunkts (eigene {@code moduleId}, die der
 *                     Elternmenues, die Menu-Root-Id)
 * @param rolleOk      Filter 1: Annotation-Rollen
 * @param rolleGrund   Grund, wenn Filter 1 Nein sagt (sonst leer)
 * @param modulRolleOk Filter 2: konfigurierte Modul-Rollen
 * @param modulRolleGrund Grund, wenn Filter 2 Nein sagt (sonst leer)
 * @param modulOk      Filter 3: Modul aktiviert
 * @param modulGrund   Grund, wenn Filter 3 Nein sagt (sonst leer)
 * @param mandantOk    Filter 4: Mandanten-White-/Blacklist
 * @param mandantGrund Grund, wenn Filter 4 Nein sagt, sonst ein Hinweis (z.B. Root-Ausnahme)
 * @param sichtbar     Gesamtergebnis — die UND-Verknuepfung aller vier Filter
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

    // Auftrag Daniel, 29.08.2026: Die beiden abgeleiteten Werte heissen bewusst NICHT getXxx().
    // Diese Klasse ist ein Record, und der RecordELResolver (Jakarta EL 6) loest #{z.modulKeysText}
    // ausschliesslich ueber eine parameterlose Methode NAMENS modulKeysText() auf — Bean-Getter
    // kennt er nicht. Mit getModulKeysText() flog die Diagnose-Seite beim Rendern mit
    // PropertyNotFoundException auseinander und blieb leer (guild PROD, 29.08.2026).
    // MenuDiagnoseZeileElTest haelt diese Zusage fest.

    /**
     * Die Modul-Keys als Text fuer die Tabelle.
     *
     * @return kommaseparierte Modul-Keys, oder {@code "—"} wenn keine bekannt sind
     */
    public String modulKeysText() {
        return modulKeys == null || modulKeys.isEmpty() ? "—" : String.join(", ", modulKeys);
    }

    /**
     * Der erste Filter, der Nein sagt — die Antwort auf „warum sehe ich das nicht?".
     *
     * @return Klartext-Grund, oder {@code ""} wenn der Menuepunkt sichtbar ist
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

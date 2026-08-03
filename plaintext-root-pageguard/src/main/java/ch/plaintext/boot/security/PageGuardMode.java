/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

/**
 * Betriebsart des Seiten-Zugriffsschutzes (Karte 308).
 *
 * <p>Unabhaengig vom Modus gilt immer:
 * <ul>
 *   <li>Menue-Links werden kanonisch verglichen (Endungen {@code .htm}/{@code .html}/{@code .xhtml}/
 *       {@code .jsf} und ein fuehrender Slash werden auf beiden Seiten ignoriert). Vorher schlug
 *       der Vergleich bei jedem Link fehl, der nicht exakt auf {@code .html} endete.</li>
 *   <li>Eine Exception bei der Pruefung fuehrt zur <b>Verweigerung</b> (vorher: erlauben).</li>
 *   <li>Allowlist und View-Aliase werden ausgewertet.</li>
 * </ul>
 *
 * @author plaintext.ch
 */
public enum PageGuardMode {

    /**
     * Framework-Default. Eine View ohne Menuezuordnung, ohne Alias und ohne Allowlist-Eintrag
     * wird <b>erlaubt</b>, aber mit WARN protokolliert. Eltern-Rollen werden nicht vererbt.
     *
     * <p>Dieser Modus existiert, damit eine konsumierende App nach dem Framework-Update nicht
     * schlagartig alle Detail-/Edit-Views aussperrt, die (noch) keinen Menueeintrag haben. Der
     * Startup-Report {@code PageAccessGuardStartupReport} listet diese Views beim Boot auf.
     */
    REPORT,

    /**
     * Fail-closed. Eine View ohne Menuezuordnung, ohne Alias und ohne Allowlist-Eintrag wird
     * <b>verweigert</b>. Zusaetzlich werden die Rollen des Elternmenues vererbt: ein Menuepunkt
     * <b>ohne eigene {@code roles}</b> unter einem rollenbeschraenkten Elternmenue ist nur
     * erreichbar, wenn auch das Elternmenue sichtbar ist (so wie im gerenderten Menue, wo ein
     * unsichtbares Elternmenue alle Kinder verbirgt). Deklariert ein Menuepunkt eigene
     * {@code roles}, sind diese abschliessend — damit bleibt eine bewusst breiter erreichbare
     * Seite unter einem eingeschraenkten Elternmenue moeglich (z.B. {@code notifications.html},
     * das die Topbar-Glocke fuer jeden User verlinkt).
     */
    STRICT
}

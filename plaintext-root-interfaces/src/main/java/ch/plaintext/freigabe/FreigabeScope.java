/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe;

/**
 * Sichtbarkeits-Scope für das modulübergreifende Freigeben („Exportieren") einer Verbindung/Ressource an
 * andere z-Module – ein wiederverwendbares Muster: Eine Ressource (z. B. ein Mailkonto) kann gar nicht, nur
 * dem Ersteller <i>persönlich</i> oder dem ganzen <i>Mandanten</i> zur Verfügung gestellt werden. Andere
 * Module konsumieren die Ressource nur über die jeweiligen Interfaces und prüfen die Sichtbarkeit hier zentral.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public enum FreigabeScope {

    /** Nicht für andere Module freigegeben (Default). */
    KEINE,

    /** Nur der Ersteller selbst (persönlich). */
    PERSOENLICH,

    /** Alle Mitglieder des Mandanten. */
    MANDAT,

    /**
     * Systemweit: nur ROOT darf solche Ressourcen anlegen/sehen (z. B. System-Mailkonten für
     * Passwort-Reset-/Login-Link-Mails). Wird NICHT über den generischen Modul-Freigabe-Pfad
     * ({@link #erlaubt}) an z-Module gegeben; die Nutzung erfolgt ausschliesslich systemseitig.
     */
    GLOBAL;

    /**
     * Darf {@code aktuellerUser} (im {@code aktuellerMandat}) eine so freigegebene Ressource konsumieren, die
     * {@code ersteller} im {@code ressourcenMandat} gehört? Die Mandantengrenze gilt immer; {@code KEINE}
     * erlaubt nie; {@code MANDAT} erlaubt allen im Mandat; {@code PERSOENLICH} nur dem Ersteller.
     *
     * @param ressourcenMandat Mandant der Ressource
     * @param ersteller        Ersteller/Eigentümer der Ressource (z. B. {@code createdBy})
     * @param aktuellerMandat  Mandant des konsumierenden Kontexts
     * @param aktuellerUser    konsumierender Benutzer
     * @return {@code true}, wenn der Zugriff erlaubt ist
     */
    public boolean erlaubt(String ressourcenMandat, String ersteller, String aktuellerMandat, String aktuellerUser) {
        // KEINE nie; GLOBAL ist system-only (kein generischer Modul-Zugriff); sonst gilt die Mandantengrenze.
        if (this == KEINE || this == GLOBAL || aktuellerMandat == null || !aktuellerMandat.equalsIgnoreCase(ressourcenMandat)) {
            return false;
        }
        if (this == MANDAT) {
            return true;
        }
        return aktuellerUser != null && aktuellerUser.equalsIgnoreCase(ersteller); // PERSOENLICH
    }
}

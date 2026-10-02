/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.ablagen;

import java.io.IOException;
import java.util.List;

/**
 * Eine eingerichtete Speicher-Ablage (Karte 1406), z. B. ein Ordner in der Nextcloud.
 *
 * <p>Module (Draw.io, Exporte …) speichern darüber Dateien, ohne Zugangsdaten oder Server zu kennen;
 * die Ablage richtet ROOT unter <i>Root → Sidecars</i> ein. Alle Pfade sind <b>relativ</b> zum dort
 * eingetragenen Pfad, mit {@code /} getrennt; {@code ..}, absolute Pfade und leere Teile werden
 * abgewiesen, eine Ablage kommt nie aus ihrem Ordner heraus.</p>
 */
public interface DateiAblage {

    /** @return Name der Ablage, z. B. {@code nextcloud-drawio} */
    String name();

    /**
     * Schreibt eine Datei; fehlende Ordner werden angelegt, eine vorhandene Datei überschrieben.
     *
     * @param inhaltTyp Medientyp, z. B. {@code image/png}; {@code null} = {@code application/octet-stream}
     */
    void schreibe(String pfad, byte[] daten, String inhaltTyp) throws IOException;

    /** @return der Inhalt einer Datei (höchstens 50 MB) */
    byte[] lies(String pfad) throws IOException;

    /** @return {@code true}, wenn unter dem Pfad eine Datei oder ein Ordner liegt */
    boolean existiert(String pfad) throws IOException;

    /** @param ordner relativer Ordner, leer = Wurzel der Ablage; Ergebnis ohne den Ordner selbst */
    List<AblageEintrag> liste(String ordner) throws IOException;

    /** Löscht eine Datei; bei Nextcloud landet sie im Papierkorb. */
    void loesche(String pfad) throws IOException;
}

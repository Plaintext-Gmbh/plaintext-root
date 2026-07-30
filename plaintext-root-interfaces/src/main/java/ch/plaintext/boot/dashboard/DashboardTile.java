/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation, um Klassen als Dashboard-Kacheln zu markieren – analog zu
 * {@link ch.plaintext.boot.menu.MenuAnnotation}. Die annotierte Klasse wird beim Start
 * automatisch gefunden und als Kachel auf der Startseite registriert.
 * <p>
 * Die Sichtbarkeit folgt demselben Mechanismus wie die Menüs: Rollen plus der
 * {@link ch.plaintext.MenuVisibilityProvider} (mandatsspezifische Menüsteuerung). Eine Kachel
 * ist also nur sichtbar, wenn das zugehörige Menü für den Mandanten aktiv ist.
 *
 * @author plaintext.ch
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DashboardTile {

    /**
     * Eindeutige technische ID der Kachel. Über diese ID kann ein
     * {@link DashboardTileDataProvider} die Kachel mit dynamischen Inhalten anreichern.
     *
     * @return die Kachel-ID
     */
    String id() default "";

    /**
     * Titel/Überschrift der Kachel.
     *
     * @return der Titel
     */
    String title() default "Dashboard";

    /**
     * Icon der Kachel (PrimeFaces-Icon-Klasse, z. B. {@code pi pi-map}).
     *
     * @return die Icon-Klasse
     */
    String icon() default "";

    /**
     * Optionale Bild-URL, die als Kopfbild der Kachel angezeigt wird. Externe Bilder müssen in
     * der CSP erlaubt sein – im Zweifel leer lassen und ein Icon verwenden.
     *
     * @return die Bild-URL
     */
    String image() default "";

    /**
     * Haupt-Link der Kachel (z. B. {@code bieler-map.html}). Wenn kein Provider explizite Aktionen
     * setzt, wird daraus eine Standard-Aktion erzeugt.
     *
     * @return der Navigations-Link
     */
    String link() default "";

    /**
     * Reihenfolge der Kachel (kleinere Werte erscheinen zuerst).
     *
     * @return die Sortierreihenfolge
     */
    int order() default 100;

    /**
     * Rollen, die diese Kachel sehen dürfen (leer = für alle sichtbar).
     *
     * @return Array von Rollennamen
     */
    String[] roles() default {};

    /**
     * Voller Menü-Titel, gegen den die mandatsspezifische Sichtbarkeit geprüft wird
     * (z. B. {@code "Lauftage"} oder {@code "Root | Mandate"}). So teilen sich Kachel und Menü
     * dieselbe Sichtbarkeitsregel – ohne zusätzliche Tabelle.
     * <p>
     * <strong>Faktisch verpflichtend:</strong> Der Wert muss exakt einem registrierten Menü-Titel
     * (inkl. Hierarchie, siehe {@link ch.plaintext.MenuRegistry#getAllMenuTitles()}) entsprechen.
     * Nur dann ist die Kachel-Sichtbarkeit nachvollziehbar an die Menü-Sichtbarkeit gekoppelt: Wird
     * das Menü für einen Mandanten ausgeblendet, verschwindet auch die Kachel.
     * <p>
     * Ist der Wert leer, fällt die Prüfung auf {@link #title()} zurück; passt der Titel zu keinem
     * registrierten Menü-Titel, bleibt die Kachel im Blacklist-Standard sichtbar (fail-open). Ein
     * solcher Mismatch wird beim Start vom {@code TileVisibilityValidator} als WARN protokolliert.
     *
     * @return der Menü-Titel für die Sichtbarkeitsprüfung
     */
    String menuTitle() default "";
}

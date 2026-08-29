/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings;

/**
 * Schluessel des {@link ISettingsService}, die mehr als ein Modul liest oder schreibt.
 *
 * <p>Zustandsbericht 29.08.2026: {@code branding.i18n.enabled} und {@code i18n.enabled} standen
 * als Konstanten doppelt in {@code BrandingService} (plaintext-admin-settings, schreibt den
 * Schalter aus dem Setup) und {@code I18nService} (plaintext-admin-i18n, liest ihn fuer die
 * Topbar). Zwei Kopien desselben Strings sind genau die Konstellation, in der die beiden Module
 * sich einmal „nicht sahen" — der Sprachwechsel blieb sichtbar, obwohl er im Setup aus war. Die
 * Module haengen beide an plaintext-root-interfaces, also wohnt der Schluessel hier, einmal.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class SettingsKeys {

    /**
     * Sprachwechsel (Topbar-Symbol, uebersetzte Menuetitel, {@code i18n.t()}) an/aus — je Mandant,
     * gesetzt im Setup („Sprachwechsel anzeigen"). Hat Vorrang vor {@link #I18N_ENABLED_LEGACY}.
     */
    public static final String I18N_ENABLED = "branding.i18n.enabled";

    /**
     * Aelterer, globaler Schluessel desselben Schalters. Bleibt als Rueckfallebene gueltig, damit
     * Instanzen mit altem Setting-Bestand nicht ploetzlich anders laufen; neu geschrieben wird er
     * nicht mehr.
     */
    public static final String I18N_ENABLED_LEGACY = "i18n.enabled";

    private SettingsKeys() {
    }
}

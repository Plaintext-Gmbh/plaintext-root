/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings;

/**
 * Keys of the {@link ISettingsService} that more than one module reads or writes.
 *
 * <p>Status report 29.08.2026: {@code branding.i18n.enabled} and {@code i18n.enabled} existed as
 * duplicate constants in {@code BrandingService} (plaintext-admin-settings, writes the switch from
 * the setup page) and {@code I18nService} (plaintext-admin-i18n, reads it for the topbar). Two
 * copies of the same string are exactly the constellation in which the two modules once "did not
 * see each other" — the language switcher stayed visible although it was switched off in the
 * setup. Both modules depend on plaintext-root-interfaces, so the key lives here, once.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class SettingsKeys {

    /**
     * Language switcher (topbar icon, translated menu titles, {@code i18n.t()}) on/off — per
     * tenant, set in the setup ("Sprachwechsel anzeigen"). Takes precedence over
     * {@link #I18N_ENABLED_LEGACY}.
     */
    public static final String I18N_ENABLED = "branding.i18n.enabled";

    /**
     * Older, global key of the same switch. It remains valid as a fallback so that instances with
     * legacy settings do not suddenly behave differently; it is no longer written.
     */
    public static final String I18N_ENABLED_LEGACY = "i18n.enabled";

    /**
     * Basis-URL der Paperless-ngx-Instanz, ohne abschliessenden Schraegstrich, z.B.
     * {@code https://paperless.plaintext.ch}. Gepflegt wird sie im Settings-Modul, je Mandant
     * oder global.
     *
     * <p><b>Warum der Schluessel hier wohnt.</b> Die Adresse stand in plaintext-app dreimal fest
     * verdrahtet — in {@code PaperlessProperties}, im Fallback von {@code PaperlessClient} und in
     * {@code OcrSettings}. Drei Kopien derselben Adresse sind genau die Konstellation, in der eine
     * davon stehen bleibt: am 01.09.2026 zeigten die Beleg- und Quittungs-Verlinkungen noch auf
     * {@code paper.plaintext.ch}, obwohl die Instanz unter {@code paperless.plaintext.ch}
     * erreichbar ist (Meldung Daniel). Der Schluessel gehoert deshalb dorthin, wo ihn jede
     * Anwendung liest, die root einbindet — einmal.</p>
     */
    public static final String PAPERLESS_URL = "paperless.url";

    /** Vorgabe fuer {@link #PAPERLESS_URL}, wenn im Settings-Modul nichts gepflegt ist. */
    public static final String PAPERLESS_URL_DEFAULT = "https://paperless.plaintext.ch";

    private SettingsKeys() {
    }
}

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

    /**
     * Die eigene oeffentliche Adresse dieser Installation, ohne abschliessenden Schraegstrich,
     * z.B. {@code https://app.plaintext.ch} oder {@code https://guild.plaintext.ch}. Gepflegt im
     * Settings-Modul, je Mandant oder global.
     *
     * <p><b>Wofuer.</b> Jeder Link, den eine Anwendung nach draussen gibt — in Mails, in oeffentlichen
     * JSON-Antworten, in Kalender-Abonnements, in QR-Codes — braucht seine eigene Adresse. Am
     * 02.09.2026 stand sie in den Repos <b>29-mal fest verdrahtet</b> (app 21, guild 7, root 1), und
     * jedes Modul loeste es anders: mal eine eigene Property ({@code bieler.public-base-url},
     * {@code plaintext.events.base-url}, {@code plaintext.lists.base-url}), mal ein Modul-Setting,
     * mal eine Konstante, mal ein Rueckfallwert mitten in einer Backing Bean. Auftrag Daniel:
     * „bei allen guild links und auch alle anderen links in den apps settings app.ownhost verwendet".
     *
     * <p><b>Keine gemeinsame Vorgabe.</b> Anders als {@link #PAPERLESS_URL} gibt es hier bewusst
     * kein {@code _DEFAULT}: app und guild laufen unter verschiedenen Adressen, eine gemeinsame
     * Vorgabe waere fuer die eine oder andere Seite immer falsch. Die Vorgabe bleibt beim Aufrufer,
     * siehe {@code EigeneAdresse}.
     */
    public static final String APP_OWNHOST = "app.ownhost";

    /**
     * Reservierter Mandant-Wert fuer eine Einstellung, die <b>fuer alle Mandanten</b> gilt
     * (Karte 1063, Auftrag Daniel 05.09.2026: „fuer Settings braucht es somit noch einen neuen
     * scope global, gleich wie bei Cron, welcher fuer alle mandate gelten kann").
     *
     * <p><b>Warum ein Mandant-Wert und kein eigenes Feld.</b> Genau so loest es der Cron seit
     * jeher: {@code CronController} ergaenzt die Mandantenliste um {@code "global"}, und die Zeile
     * in {@code cron_config} traegt diesen Wert in derselben Spalte wie jeder andere Mandant. In
     * app PROD stehen dort am 05.09.2026 sechs solche Zeilen — das Muster ist im Einsatz, nicht
     * bloss gedacht. Ein zweites Konzept fuer dieselbe Frage waere teurer als der reservierte Wert.
     *
     * <p><b>Wirkung:</b> Beim <b>Lesen</b> einer Einstellung wird der Schluessel zuerst beim
     * Mandanten gesucht und, wenn es dort nichts gibt, unter diesem Wert. Beim <b>Schreiben,
     * Loeschen und Pruefen</b> gilt er nicht — sonst loeschte das Entfernen einer
     * mandantenspezifischen Einstellung den gemeinsamen Eintrag mit.
     */
    public static final String MANDAT_GLOBAL = "global";

    private SettingsKeys() {
    }
}

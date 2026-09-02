/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.settings.ISettingsService;
import ch.plaintext.settings.SettingsKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Die eigene oeffentliche Adresse dieser Installation, an einer Stelle aufgeloest.
 *
 * <p><b>Auftrag Daniel, 02.09.2026:</b> „Ich moechte das bei allen guild links und auch alle
 * anderen links in den apps settings app.ownhost verwendet wird" (Karte 1046).
 *
 * <p><b>Warum es das braucht.</b> Am 02.09.2026 stand die eigene Adresse in den Repos 29-mal fest
 * verdrahtet — app 21, guild 7, root 1 — und jedes Modul loeste es anders: eigene Properties
 * ({@code bieler.public-base-url}, {@code plaintext.events.base-url},
 * {@code plaintext.lists.base-url}), ein Modul-Setting, eine Konstante, dazu Rueckfallwerte mitten
 * in Backing Beans. Wer eine Installation umzieht, muss heute alle 29 finden.
 *
 * <h2>Die Rangfolge</h2>
 * <ol>
 *   <li>{@code app.ownhost} aus dem Settings-Modul, zuerst mandantenspezifisch, sonst global —
 *       zur Laufzeit aenderbar, ohne Deployment</li>
 *   <li>{@code plaintext.app.ownhost} aus der Konfiguration</li>
 *   <li>die Vorgabe, die der Aufrufer mitgibt</li>
 * </ol>
 *
 * <p><b>Die Vorgabe kommt vom Aufrufer und nicht von hier.</b> app laeuft unter
 * {@code https://app.plaintext.ch}, guild unter {@code https://guild.plaintext.ch}; eine
 * gemeinsame Vorgabe in root waere fuer die eine oder andere Seite immer falsch. Jedes Modul
 * behaelt damit seine bisherige Property als zweite Stufe — wer heute
 * {@code plaintext.events.base-url} gesetzt hat, gibt sie einfach als Vorgabe weiter und merkt
 * nichts.
 *
 * <p><b>Die Adresse darf nie der Grund sein, warum etwas nicht geht.</b> Fehlt das Settings-Modul,
 * ist kein Mandant im Kontext oder wirft die Abfrage, faellt diese Klasse still auf die naechste
 * Stufe zurueck. Ein Link mit der alten Adresse ist unschoen; eine Ausnahme beim Mailversand ist
 * schlimmer.
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@Component
@Slf4j
public class EigeneAdresse {

    private final ObjectProvider<ISettingsService> settingsService;

    /**
     * Zweite Stufe: gemeinsame Konfigurations-Property. Leer bedeutet „nicht gesetzt" — dann
     * entscheidet die Vorgabe des Aufrufers.
     */
    @Value("${plaintext.app.ownhost:}")
    private String ausKonfiguration;

    public EigeneAdresse(ObjectProvider<ISettingsService> settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Die eigene Adresse, ohne abschliessenden Schraegstrich.
     *
     * @param vorgabe was gilt, wenn weder Einstellung noch Property gesetzt sind — in aller Regel
     *                die bisherige Konstante des Moduls, damit sich ohne Pflege nichts aendert
     * @return die Adresse; nie {@code null}, solange die Vorgabe nicht {@code null} ist
     */
    public String basis(String vorgabe) {
        String url = ausSettings();
        if (istLeer(url)) {
            url = ausKonfiguration;
        }
        if (istLeer(url)) {
            url = vorgabe;
        }
        return ohneEndSlash(url);
    }

    /**
     * Liest {@link SettingsKeys#APP_OWNHOST} aus dem Settings-Modul: zuerst mandantenspezifisch,
     * sonst global. {@code null}, wenn nichts gepflegt oder das Modul nicht da ist.
     *
     * <p><b>Der Mandant wird SEPARAT abgesichert.</b> Ohne Security-Kontext — Hintergrund-Job,
     * Test, Systemaufruf — wirft {@code PlaintextSecurityHolder.getMandat()}. Laege dieser Aufruf
     * im selben {@code try} wie die globale Abfrage, wuerde der globale Wert nie gelesen. Genau
     * dieser Fehler ist am 01.09.2026 bei {@code PaperlessClient} aufgetreten und dort von einem
     * Test gefangen worden; hier steht er von Anfang an richtig.
     */
    private String ausSettings() {
        ISettingsService service = settingsService == null ? null : settingsService.getIfAvailable();
        if (service == null) {
            return null;
        }
        String mandat = null;
        try {
            mandat = PlaintextSecurityHolder.getMandat();
        } catch (RuntimeException e) {
            log.debug("Kein Mandant im Kontext, nehme die globale Einstellung", e);
        }
        try {
            String wert = mandat == null ? null : service.getString(SettingsKeys.APP_OWNHOST, mandat);
            if (istLeer(wert)) {
                wert = service.getString(SettingsKeys.APP_OWNHOST);
            }
            return wert;
        } catch (RuntimeException e) {
            log.debug("Settings-Schluessel {} nicht lesbar, nehme die Konfiguration",
                    SettingsKeys.APP_OWNHOST, e);
            return null;
        }
    }

    /**
     * Schneidet abschliessende Schraegstriche ab.
     *
     * <p>Statisch und oeffentlich, weil mehrere Module heute eine eigene Fassung davon halten
     * ({@code BasisUrl.ohneEndSlash} in app) — sie koennen darauf umstellen, ohne diese Bohne zu
     * brauchen.
     */
    public static String ohneEndSlash(String url) {
        if (url == null) {
            return null;
        }
        String w = url.trim();
        while (w.endsWith("/")) {
            w = w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static boolean istLeer(String wert) {
        return wert == null || wert.isBlank();
    }
}

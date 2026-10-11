/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mcp;

import ch.plaintext.arch.StabileApi;
import lombok.extern.slf4j.Slf4j;

/**
 * Karte 469: Die Zeichenkette {@code "null"} ist kein Wert.
 *
 * <p><b>Wie sie entsteht.</b> {@code @McpToolParam} hat {@code required()} mit Default
 * {@code true}. Bis Karte 469 war deshalb jeder Parameter ein Pflichtfeld — auch die 73, deren
 * Beschreibung „optional", „or empty" oder „null = keep" versprach. Ein Aufrufer, der nichts
 * uebergeben wollte, konnte das Feld gar nicht weglassen. Ihm blieben nur zwei schlechte
 * Ersatzwege: der Leerstring oder das Wort {@code "null"}.
 *
 * <p>Beide sind in diesem Repo bereits eingetreten:
 * <ul>
 *   <li>Am 02.08.2026 wurde Kontakt 16 zu „null null" mit ueberschriebener E-Mail und
 *       ueberschriebenem Geburtsdatum — still, mit HTTP 200 (Karte 467).</li>
 *   <li>Event 3812 bekam das Literal {@code "null"} als RRULE (Karte 390).</li>
 * </ul>
 *
 * <p><b>Warum es diese Klasse gibt und nicht nur die drei Einzelfixes.</b> Auf beide Vorfaelle
 * folgte je ein privater Helfer in genau der Klasse, in der es geknallt hatte —
 * {@code KontakteMcpTools.ohneLiteral} und {@code KalenderHostMcpTools.istNullLiteral}. Beide
 * waren richtig und beide halfen nur ihrem eigenen Werkzeug. Die Ursache war nie
 * werkzeugspezifisch. Diese Klasse ist derselbe Gedanke an einer Stelle, die alle Module
 * erreichen.
 *
 * <p>Das Schema ist mit Karte 469 repariert; optionale Felder lassen sich jetzt wirklich
 * weglassen. Der Schutz bleibt trotzdem noetig: Werkzeugbeschreibungen wandern in Modell-Caches
 * und in fremde Client-Konfigurationen und ueberleben dort ihre Korrektur.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@StabileApi("Literale fuer MCP-Werkzeuge (Karte 1504, aus app-interfaces)")
public final class McpLiterale {

    private McpLiterale() {
    }

    /**
     * Gibt {@code null} zurueck, wenn der Wert die Zeichenkette {@code "null"} oder
     * {@code "undefined"} ist — sonst den Wert unveraendert.
     *
     * <p>Umschliessender Leerraum wird beim Vergleich ignoriert: Wer {@code " null"} schickt,
     * meint dasselbe wie der, der {@code "null"} schickt.
     *
     * <p>Ein Leerstring bleibt hier ein Leerstring. Er ist an manchen Stellen eine eigene,
     * gewollte Aussage — bei {@code update_event.beschreibung} etwa „Beschreibung entfernen".
     * Wer ihn als „nicht gesetzt" behandelt haben will, nimmt {@link #nichtGesetzt(String)}.
     */
    public static String ohneNullLiteral(String wert) {
        if (wert == null) {
            return null;
        }
        String t = wert.trim();
        if ("null".equalsIgnoreCase(t) || "undefined".equalsIgnoreCase(t)) {
            log.warn("MCP: Literal \"{}\" als Feldwert empfangen und als 'nicht gesetzt' behandelt "
                    + "(Karte 469). Feld weglassen statt \"{}\" senden.", wert, wert);
            return null;
        }
        return wert;
    }

    /**
     * Wie {@link #ohneNullLiteral(String)}, liefert aber statt {@code null} den Leerstring.
     *
     * <p>Fuer Felder, die ohnehin per {@code wert != null ? wert : ""} auf {@code ""}
     * zurueckfallen — dort waere ein zusaetzlicher {@code null}-Zweig nur ein Umweg.
     */
    public static String ohneNullLiteralOderLeer(String wert) {
        String bereinigt = ohneNullLiteral(wert);
        return bereinigt != null ? bereinigt : "";
    }

    /**
     * {@code true}, wenn der Wert fehlt, leer ist oder das Literal {@code "null"} enthaelt.
     *
     * <p>Fuer Aktualisierungs-Werkzeuge, bei denen ein nicht gesetztes Feld „unveraendert lassen"
     * bedeutet. Dort ist der Leerstring KEINE gewollte Aussage, sondern der Ersatzweg eines
     * Aufrufers, der das Feld nicht weglassen konnte — und ihn als echten Wert zu speichern,
     * loescht Daten. Bei {@code update_wiki_seite.inhaltMd} waere das der gesamte Seiteninhalt
     * gewesen.
     *
     * <p>Gleichbedeutend mit dem bereits bewaehrten {@code nichtGesetzt} aus
     * {@code KalenderHostMcpTools} (Karte 390) — nur eben allgemein verfuegbar.
     */
    public static boolean nichtGesetzt(String wert) {
        return ohneNullLiteral(wert) == null || wert.trim().isEmpty();
    }
}

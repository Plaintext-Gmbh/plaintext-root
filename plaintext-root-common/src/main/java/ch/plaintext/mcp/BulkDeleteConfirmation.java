/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wiederverwendbare Bestaetigungs-Pruefung fuer Mengen-/Cascade-Loeschungen ueber MCP — geht auf
 * das urspruengliche Muster in {@code RechnungenMcpTools#delete_alle_stornierten_rechnungen}
 * zurueck (Exact-Phrase statt Zwei-Schritt-Token, damit ein LLM-Client die Phrase direkt aus der
 * abgelehnten Antwort lesen und im zweiten Aufruf mitgeben kann).
 *
 * <p><b>Konvention fuer jedes Mengen-/Cascade-Lösch-Tool:</b></p>
 * <ol>
 *   <li>Optionaler Parameter {@code bestaetigung}; ohne/mit falscher Phrase liefert
 *       {@link #check} einen Dry-Run-Report ({@code confirmed() == false}) — der Aufrufer gibt
 *       diesen Report unveraendert als Tool-Ergebnis zurueck, OHNE etwas zu loeschen.</li>
 *   <li>Bei korrekter Phrase ({@code confirmed() == true}) fuehrt der Aufrufer die Loeschung
 *       selbst aus und ruft danach IMMER
 *       {@code DestructiveActionAuditService.logDestructiveAction("MCP", ...)} auf.</li>
 *   <li>Batch-Tools mit id-Liste: bis {@link #BULK_THRESHOLD} Ids direkt wie bisher ausfuehren
 *       (Alltag nicht ausbremsen), erst darueber eine Bestaetigung verlangen.</li>
 *   <li>Cascade-Tools (ein Elternobjekt loescht mehrere Kinder mit): Bestaetigung verlangen,
 *       sobald die Kinderzahl &gt; 0 ist.</li>
 * </ol>
 */
public final class BulkDeleteConfirmation {

    /** Ab dieser Batch-Groesse (id-Liste) verlangen Batch-Tools ebenfalls eine Bestaetigung. */
    public static final int BULK_THRESHOLD = 10;

    private BulkDeleteConfirmation() {
    }

    /** Die vom Aufrufer erwartete Phrase fuer eine gegebene Aktionsbeschreibung. */
    public static String phrase(String aktionsBeschreibung) {
        return "ja wirklich " + aktionsBeschreibung;
    }

    /**
     * Prueft die Bestaetigungsphrase gegen die Konvention {@code "ja wirklich " + aktionsBeschreibung}.
     *
     * @param bestaetigung        vom Aufrufer uebergebener Wert (kann {@code null} sein)
     * @param aktionsBeschreibung Aktionsbeschreibung, z. B. {@code "alle stornierten löschen"}
     * @param betroffenAnzahl     Anzahl der betroffenen Datensaetze (fuer den Dry-Run-Report)
     * @return {@code confirmed() == true} bei korrekter Phrase (nichts weiter zu tun ausser
     *         Loeschen + Audit); sonst ein fertiger Dry-Run-Report als Tool-Rueckgabe
     */
    public static BulkCheckResult check(String bestaetigung, String aktionsBeschreibung, long betroffenAnzahl) {
        String erwartet = phrase(aktionsBeschreibung);
        if (erwartet.equals(bestaetigung)) {
            return new BulkCheckResult(true, null);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("geloescht_anzahl", 0);
        report.put("betroffen_anzahl", betroffenAnzahl);
        report.put("hinweis", "ABGEBROCHEN: nichts geloescht. Zum wirklichen, unwiderruflichen Loeschen "
                + "bestaetigung = '" + erwartet + "' setzen.");
        return new BulkCheckResult(false, report);
    }

    /**
     * @param confirmed    {@code true}, wenn die Phrase korrekt war — der Aufrufer darf loeschen
     * @param dryRunReport fertiger Report als Tool-Rueckgabe, oder {@code null} wenn {@code confirmed}
     */
    public record BulkCheckResult(boolean confirmed, Map<String, Object> dryRunReport) {
    }
}

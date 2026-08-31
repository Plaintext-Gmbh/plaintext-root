/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reusable confirmation check for bulk/cascade deletions through MCP — it goes back to the
 * original pattern in {@code RechnungenMcpTools#delete_alle_stornierten_rechnungen} (an exact
 * phrase instead of a two-step token, so that an LLM client can read the phrase straight out of
 * the rejected response and pass it along in a second call).
 *
 * <p><b>Convention for every bulk/cascade delete tool:</b></p>
 * <ol>
 *   <li>Optional parameter {@code bestaetigung}; without it or with a wrong phrase
 *       {@link #check} returns a dry-run report ({@code confirmed() == false}) — the caller
 *       returns that report unchanged as the tool result, WITHOUT deleting anything.</li>
 *   <li>With the correct phrase ({@code confirmed() == true}) the caller performs the deletion
 *       itself and ALWAYS calls
 *       {@code DestructiveActionAuditService.logDestructiveAction("MCP", ...)} afterwards.</li>
 *   <li>Batch tools with an id list: up to {@link #BULK_THRESHOLD} ids run directly as before
 *       (do not slow everyday work down), only beyond that is a confirmation required.</li>
 *   <li>Cascade tools (one parent object deletes several children along with it): require a
 *       confirmation as soon as the number of children is &gt; 0.</li>
 * </ol>
 */
public final class BulkDeleteConfirmation {

    /** From this batch size (id list) on, batch tools likewise require a confirmation. */
    public static final int BULK_THRESHOLD = 10;

    private BulkDeleteConfirmation() {
    }

    /** The phrase expected from the caller for a given action description. */
    public static String phrase(String aktionsBeschreibung) {
        return "ja wirklich " + aktionsBeschreibung;
    }

    /**
     * Checks the confirmation phrase against the convention {@code "ja wirklich " + aktionsBeschreibung}.
     *
     * @param bestaetigung        value passed in by the caller (may be {@code null})
     * @param aktionsBeschreibung action description, e.g. {@code "alle stornierten löschen"}
     * @param betroffenAnzahl     number of affected records (for the dry-run report)
     * @return {@code confirmed() == true} on a correct phrase (nothing left to do but delete and
     *         audit); otherwise a ready-made dry-run report as the tool's return value
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
     * @param confirmed    {@code true} when the phrase was correct — the caller may delete
     * @param dryRunReport ready-made report as the tool's return value, or {@code null} when {@code confirmed}
     */
    public record BulkCheckResult(boolean confirmed, Map<String, Object> dryRunReport) {
    }
}

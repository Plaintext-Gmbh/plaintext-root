/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mcp;

import ch.plaintext.mcp.BulkDeleteConfirmation.BulkCheckResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkDeleteConfirmationTest {

    @Test
    void phrase_hatEinheitlichesPraefix() {
        assertThat(BulkDeleteConfirmation.phrase("alle stornierten löschen"))
                .isEqualTo("ja wirklich alle stornierten löschen");
    }

    @Test
    void check_korrektePhrase_confirmedOhneReport() {
        BulkCheckResult res = BulkDeleteConfirmation.check("ja wirklich alle stornierten löschen",
                "alle stornierten löschen", 5);

        assertThat(res.confirmed()).isTrue();
        assertThat(res.dryRunReport()).isNull();
    }

    @Test
    void check_falschePhrase_liefertDryRunReportUndLoeschtNichts() {
        BulkCheckResult res = BulkDeleteConfirmation.check("quatsch", "alle stornierten löschen", 5);

        assertThat(res.confirmed()).isFalse();
        assertThat(res.dryRunReport()).containsEntry("geloescht_anzahl", 0);
        assertThat(res.dryRunReport()).containsEntry("betroffen_anzahl", 5L);
        assertThat(res.dryRunReport().get("hinweis").toString())
                .contains("ja wirklich alle stornierten löschen");
    }

    @Test
    void check_nullBestaetigung_liefertDryRunReport() {
        BulkCheckResult res = BulkDeleteConfirmation.check(null, "alle löschen", 0);

        assertThat(res.confirmed()).isFalse();
        assertThat(res.dryRunReport()).containsEntry("betroffen_anzahl", 0L);
    }

    @Test
    void bulkThreshold_istZehn() {
        assertThat(BulkDeleteConfirmation.BULK_THRESHOLD).isEqualTo(10);
    }
}

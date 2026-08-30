/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DestructiveActionAuditServiceTest {

    @Mock
    private DestructiveActionAuditRepository repo;

    @InjectMocks
    private DestructiveActionAuditService service;

    @Test
    void logDestructiveAction_speichertAlleFelder() {
        service.logDestructiveAction("MCP", "RECHNUNG_HARD_DELETE", "Rechnung", "12,13,14", "Batch-Löschung");

        ArgumentCaptor<DestructiveActionAudit> captor = ArgumentCaptor.forClass(DestructiveActionAudit.class);
        verify(repo).save(captor.capture());
        DestructiveActionAudit saved = captor.getValue();
        assertEquals("MCP", saved.getChannel());
        assertEquals("RECHNUNG_HARD_DELETE", saved.getActionType());
        assertEquals("Rechnung", saved.getEntityType());
        assertEquals("12,13,14", saved.getEntityIds());
        assertEquals("Batch-Löschung", saved.getDetail());
    }

    @Test
    void logDestructiveAction_repositoryFehler_wirdVerschluckt() {
        when(repo.save(any())).thenThrow(new RuntimeException("DB weg"));

        assertDoesNotThrow(() -> service.logDestructiveAction("UI", "KONTAKT_DELETE", "Kontakt", "1", null));
    }

    /**
     * Karte 332: swallowing the exception above is NOT enough — if the audit insert ran inside the
     * caller's transaction (Propagation.REQUIRED), its failure marked that foreign transaction as
     * rollback-only and on commit the caller lost its business change with
     * "Transaction silently rolled back" (that is how delete_email_account aborted). Only a
     * transaction of its OWN keeps this class's best-effort promise structurally.
     */
    @Test
    void logDestructiveAction_laeuftInEigenerTransaktion() throws Exception {
        Transactional tx = DestructiveActionAuditService.class
                .getMethod("logDestructiveAction", String.class, String.class, String.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertNotNull(tx, "logDestructiveAction muss transaktional sein");
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation(),
                "Audit-Log muss in einer EIGENEN Transaktion laufen, sonst reisst ein Audit-Fehler "
                        + "die fachliche Transaktion des Aufrufers mit in den Rollback (Karte 332).");
    }
}

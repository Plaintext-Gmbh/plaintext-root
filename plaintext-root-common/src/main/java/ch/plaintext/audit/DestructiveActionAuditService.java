/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schreibt Einträge ins generische {@link DestructiveActionAudit}-Log. Jede App (root/app/guild/
 * iot/schuetu) nutzt diese Bean direkt (kein Cross-Service-Call nötig — jede App schreibt in ihre
 * eigene, lokale Kopie der Tabelle, siehe Migration).
 *
 * <p>Best-effort wie beim Vorbild {@code ImpersonationAudit}: ein Fehler beim Schreiben des
 * Audit-Logs darf die eigentliche (bereits ausgeführte) destruktive Aktion NIE nachträglich
 * blockieren oder scheitern lassen.</p>
 *
 * <p><b>Warum {@link Propagation#REQUIRES_NEW} (Karte 332):</b> Mit dem vorherigen {@code REQUIRED}
 * lief der Audit-Insert in der Transaktion des Aufrufers mit. Scheiterte er, markierte das die
 * <em>fremde</em> Transaktion als {@code rollback-only} — der {@code catch}-Block unten schluckte die
 * Exception zwar, aber beim Commit bekam der Aufrufer trotzdem ein irreführendes „Transaction silently
 * rolled back because it has been marked as rollback-only" und seine fachliche Änderung war weg.
 * Genau so brach {@code delete_email_account} ab, nachdem eine fehlende ID-Generierung der
 * Audit-Tabelle jeden Insert scheitern liess. Mit einer eigenen Transaktion bleibt ein Audit-Fehler
 * strukturell auf das Audit-Log beschränkt — das Best-effort-Versprechen dieser Klasse hält dann auch
 * dann noch, wenn die Audit-Tabelle selbst kaputt oder gar nicht vorhanden ist.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DestructiveActionAuditService {

    private final DestructiveActionAuditRepository repo;

    /**
     * @param channel    {@code UI} oder {@code MCP}
     * @param actionType frei wählbarer Aktions-Bezeichner, z. B. {@code RECHNUNG_HARD_DELETE}
     * @param entityType betroffener Entity-Typ, z. B. {@code Rechnung}
     * @param entityIds  betroffene IDs/Anzahl als Text, oder {@code null}
     * @param detail     Freitext (Kontext, Bestätigungsphrase, Fehlermeldung), oder {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logDestructiveAction(String channel, String actionType, String entityType,
                                     String entityIds, String detail) {
        try {
            DestructiveActionAudit a = new DestructiveActionAudit();
            a.setChannel(channel);
            a.setActionType(actionType);
            a.setEntityType(entityType);
            a.setEntityIds(entityIds);
            a.setDetail(detail);
            repo.save(a);
        } catch (Exception e) {
            log.warn("Audit-Log fehlgeschlagen für actionType={} entityType={}: {}",
                    actionType, entityType, e.getMessage());
        }
    }
}

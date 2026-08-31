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
 * Writes entries into the generic {@link DestructiveActionAudit} log. Every app (root/app/guild/
 * iot/schuetu) uses this bean directly (no cross-service call needed — each app writes into its
 * own local copy of the table, see the migration).
 *
 * <p>Best effort, following the example of {@code ImpersonationAudit}: a failure while writing
 * the audit log must NEVER retroactively block or break the destructive action itself, which has
 * already been carried out.</p>
 *
 * <p><b>Why {@link Propagation#REQUIRES_NEW} (Karte 332):</b> with the previous {@code REQUIRED}
 * the audit insert ran inside the caller's transaction. When it failed, that marked the
 * <em>foreign</em> transaction as {@code rollback-only} — the {@code catch} block below did swallow
 * the exception, but on commit the caller still got a misleading "Transaction silently
 * rolled back because it has been marked as rollback-only" and its business change was gone.
 * That is exactly how {@code delete_email_account} aborted, after a missing ID generation on the
 * audit table made every insert fail. With a transaction of its own, an audit failure stays
 * structurally confined to the audit log — this class then keeps its best-effort promise even
 * when the audit table itself is broken or missing altogether.</p>
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
     * @param channel    {@code UI} or {@code MCP}
     * @param actionType freely chosen action identifier, e.g. {@code RECHNUNG_HARD_DELETE}
     * @param entityType affected entity type, e.g. {@code Rechnung}
     * @param entityIds  affected IDs/count as text, or {@code null}
     * @param detail     free text (context, confirmation phrase, error message), or {@code null}
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

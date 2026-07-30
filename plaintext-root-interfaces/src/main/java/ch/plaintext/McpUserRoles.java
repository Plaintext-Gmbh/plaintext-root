/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.Set;

/**
 * Leichtgewichtiger, leak-freier Rollen-Lookup eines Benutzers per userId — für token-basierte
 * Zugriffe (MCP-BearerToken-Filter), die den {@link org.springframework.security.core.context.SecurityContext}
 * VOR der eigentlichen Verarbeitung befüllen müssen.
 *
 * <p>Hintergrund: Das MCP-JWT trägt bewusst KEINE Rollen. Damit {@link PlaintextSecurity#getAllowedMandate()}
 * für ROOT-Benutzer alle Mandate liefert, muss der Filter die echten Rollen des Token-Benutzers laden und
 * als Authorities in den Context legen. Die Implementierung liest nur die (als konvertierte Spalte EAGER
 * geladenen) Rollen — ein einzelner {@code findById}-Read, KEINE {@code @Transactional}-Kette (vermeidet den
 * bekannten Hikari-Connection-Leak des DB-gestützten Token-Validators).</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface McpUserRoles {

    /**
     * @param userId Benutzer-ID (aus dem validierten Token)
     * @return die Rollen des Benutzers (z.B. {@code ROOT}, {@code ADMIN}, {@code PROPERTY_MANDAT_xy}),
     *         oder eine leere Menge, wenn {@code userId} null ist oder kein Benutzer existiert
     */
    Set<String> rolesForUser(Long userId);
}

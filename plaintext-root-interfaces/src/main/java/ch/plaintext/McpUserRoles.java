/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.Set;

/**
 * Lightweight, leak-free role lookup for a user by userId — for token-based access (the MCP bearer
 * token filter) that has to populate the
 * {@link org.springframework.security.core.context.SecurityContext} BEFORE the actual processing.
 *
 * <p>Background: the MCP JWT deliberately carries NO roles. For
 * {@link PlaintextSecurity#getAllowedMandate()} to return all tenants for ROOT users, the filter
 * has to load the token user's real roles and put them into the context as authorities. The
 * implementation reads only the roles (EAGER-loaded as a converted column) — a single
 * {@code findById} read, NOT a {@code @Transactional} chain (which avoids the known Hikari
 * connection leak of the DB-backed token validator).</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface McpUserRoles {

    /**
     * @param userId user ID (from the validated token)
     * @return the user's roles (e.g. {@code ROOT}, {@code ADMIN}, {@code PROPERTY_MANDAT_xy}), or
     *         an empty set if {@code userId} is null or no such user exists
     */
    Set<String> rolesForUser(Long userId);
}

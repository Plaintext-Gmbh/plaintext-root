/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.McpUserRoles;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link McpUserRoles}-Implementierung: liest die Rollen eines Benutzers per {@code findById}.
 *
 * <p>{@code MyUserEntity.roles} ist eine per {@code @Convert} gespeicherte Spalte (kein Lazy-
 * ElementCollection) → wird mit der Entity-Zeile EAGER geladen. Daher genügt ein einzelner
 * Repository-Read OHNE {@code @Transactional} — kein Lazy-Init, keine offene Transaktion, kein
 * Hikari-Connection-Leak.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
@RequiredArgsConstructor
public class McpUserRolesImpl implements McpUserRoles {

    private final MyUserRepository myUserRepository;

    @Override
    public Set<String> rolesForUser(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return myUserRepository.findById(userId)
                .map(u -> (Set<String>) new HashSet<>(u.getRoles()))
                .orElseGet(Set::of);
    }
}

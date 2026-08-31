/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Root's own {@link SearchProvider}: finds users by user name or ID and links to the
 * user administration ({@code useradmin.xhtml}).
 * <p>
 * <b>For ROOT/ADMIN only</b> and cross-cutting ({@link #isMenuScoped()} {@code = false}); the
 * role/tenant visibility is enforced here by the provider itself - exactly as in the user
 * administration: ROOT sees all users, ADMIN only those of its own tenant. Without ROOT/ADMIN the
 * provider returns nothing.
 *
 * @author plaintext.ch
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSearchProvider implements SearchProvider {

    private final MyUserRepository userRepository;
    private final PlaintextSecurity security;

    @Override
    public String providerId() {
        return "users";
    }

    @Override
    public String moduleTitle() {
        return "Benutzer";
    }

    @Override
    public boolean isMenuScoped() {
        // Bound to roles/tenants, coupled to no domain module menu: secured by itself.
        return false;
    }

    @Override
    public List<SearchHit> search(String query, int limit) {
        boolean root = ifGrantedSafe("ROLE_ROOT");
        boolean admin = ifGrantedSafe("ROLE_ADMIN");
        if (!root && !admin) {
            return List.of();
        }

        String needle = query.toLowerCase();
        String currentMandat = root ? null : safeMandat();

        List<MyUserEntity> all;
        try {
            all = userRepository.findAll();
        } catch (Exception ex) {
            log.debug("Benutzer-Repository nicht verfügbar: {}", ex.getMessage());
            return List.of();
        }

        List<SearchHit> hits = new ArrayList<>();
        for (MyUserEntity user : all) {
            SearchHit hit = toHit(user, root, currentMandat, needle);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    /**
     * Builds a hit from a visible, matching user, otherwise {@code null}
     * (no user name, foreign tenant for ADMIN or no query match).
     */
    private SearchHit toHit(MyUserEntity user, boolean root, String currentMandat, String needle) {
        if (user == null || user.getUsername() == null) {
            return null;
        }
        // ADMIN: restrict strictly to the own tenant (as in the user administration).
        if (!root && (currentMandat == null || !currentMandat.equals(user.getMandat()))) {
            return null;
        }
        int score = matchScore(user, needle);
        if (score <= 0) {
            return null;
        }
        String mandat = user.getMandat();
        return new SearchHitDTO(
                user.getUsername(),
                (mandat != null && !mandat.isBlank()) ? "Mandant: " + mandat : "Benutzer #" + user.getId(),
                "useradmin.xhtml",
                "pi pi-user",
                score);
    }

    /**
     * Score: exact/prefix hit in the user name &gt; contained name; ID hit separately. 0 = no match.
     */
    private int matchScore(MyUserEntity user, String needle) {
        String name = user.getUsername().toLowerCase();
        if (name.equals(needle)) {
            return 100;
        }
        if (name.startsWith(needle)) {
            return 80;
        }
        if (name.contains(needle)) {
            return 60;
        }
        if (user.getId() != null && String.valueOf(user.getId()).equals(needle)) {
            return 90;
        }
        return 0;
    }

    private boolean ifGrantedSafe(String role) {
        try {
            return security.ifGranted(role);
        } catch (Exception _) {
            return false;
        }
    }

    private String safeMandat() {
        try {
            return security.getMandat();
        } catch (Exception _) {
            return null;
        }
    }
}

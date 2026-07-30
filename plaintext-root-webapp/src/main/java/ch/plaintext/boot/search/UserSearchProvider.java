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
 * Root-eigener {@link SearchProvider}: findet Benutzer per Benutzername oder ID und verlinkt auf die
 * Benutzerverwaltung ({@code useradmin.xhtml}).
 * <p>
 * <b>Nur für ROOT/ADMIN</b> und quer schneidend ({@link #isMenuScoped()} {@code = false}); die
 * Rollen-/Mandanten-Sichtbarkeit wird hier selbst erzwungen – exakt wie in der Benutzerverwaltung:
 * ROOT sieht alle Benutzer, ADMIN nur die des eigenen Mandanten. Ohne ROOT/ADMIN liefert der
 * Provider nichts.
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
        // Rollen-/Mandanten-gebunden, an kein Fachmodul-Menü gekoppelt: selbst abgesichert.
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
     * Baut aus einem sichtbaren, passenden Benutzer einen Treffer, sonst {@code null}
     * (kein Username, fremder Mandant für ADMIN oder kein Query-Match).
     */
    private SearchHit toHit(MyUserEntity user, boolean root, String currentMandat, String needle) {
        if (user == null || user.getUsername() == null) {
            return null;
        }
        // ADMIN: strikt auf den eigenen Mandanten begrenzen (wie in der Benutzerverwaltung).
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
     * Score: exakter/Präfix-Treffer im Benutzernamen &gt; enthaltener Name; ID-Treffer separat. 0 = kein Match.
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

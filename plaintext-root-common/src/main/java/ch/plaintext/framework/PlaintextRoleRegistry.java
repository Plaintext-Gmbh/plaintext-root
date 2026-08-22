/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sammelt die von allen Modulen deklarierten Rollen ein — das Rollen-Pendant zur Menue-Registry:
 * Wie beim Menue-System steuern Module ihren Beitrag als Spring-Bean bei
 * ({@link PlaintextRoleProvider}), und root sammelt die Beitraege zentral ein (Union,
 * dedupliziert ueber den {@linkplain PlaintextRole#normalizedName() normalisierten Namen}).
 *
 * <p>Konsumenten (z.B. die Benutzerverwaltung) erhalten damit eine vollstaendige, beschriebene
 * Rollen-Auswahl, statt dass Rollennamen freihaendig getippt werden muessen. Rollen, die kein
 * Modul (mehr) deklariert, verschwinden dadurch NICHT aus den Benutzern — die Konsumenten
 * mischen den Bestand (DB) als Fallback dazu.</p>
 *
 * @author info@plaintext.ch
 * @since 1.600.0
 */
@Component
@Slf4j
public class PlaintextRoleRegistry {

    @Autowired(required = false)
    private List<PlaintextRoleProvider> roleProviders;

    /**
     * Alle deklarierten Rollen, dedupliziert ueber den normalisierten Namen und alphabetisch
     * sortiert. Deklarieren mehrere Module dieselbe Rolle, gewinnt die erste nicht-leere
     * Beschreibung.
     *
     * @return deklarierte Rollen (nie {@code null})
     */
    public List<PlaintextRole> getDeclaredRoles() {
        Map<String, PlaintextRole> byName = new LinkedHashMap<>();
        if (roleProviders != null) {
            for (PlaintextRoleProvider provider : roleProviders) {
                collectFrom(provider, byName);
            }
        }
        List<PlaintextRole> ret = new ArrayList<>(byName.values());
        ret.sort(Comparator.comparing(PlaintextRole::normalizedName));
        return ret;
    }

    private void collectFrom(PlaintextRoleProvider provider, Map<String, PlaintextRole> byName) {
        Set<PlaintextRole> declared;
        try {
            declared = provider.getDeclaredRoles();
        } catch (Exception e) {
            log.warn("RoleProvider {} lieferte keine Rollen: {}", provider.getClass().getName(), e.getMessage());
            return;
        }
        if (declared == null) {
            return;
        }
        for (PlaintextRole role : declared) {
            if (role == null) {
                continue;
            }
            byName.merge(role.normalizedName(), role, (existing, candidate) ->
                    existing.description().isEmpty() && !candidate.description().isEmpty() ? candidate : existing);
        }
    }

    /**
     * Die normalisierten Namen aller deklarierten Rollen (lowercase, ohne {@code ROLE_}-Prefix) —
     * das Format, in dem die Benutzerverwaltung Rollen am Benutzer speichert.
     *
     * @return normalisierte Rollennamen, alphabetisch sortiert
     */
    public Set<String> getDeclaredRoleNames() {
        Set<String> ret = new LinkedHashSet<>();
        for (PlaintextRole role : getDeclaredRoles()) {
            ret.add(role.normalizedName());
        }
        return ret;
    }

    /**
     * Die Authority-Namen aller deklarierten Rollen ({@code ROLE_<UPPERCASE>}) — das Format der
     * Spring-Security-Authorities bzw. der Rollenzuteilung.
     *
     * @return Authority-Namen, alphabetisch sortiert
     */
    public Set<String> getDeclaredAuthorityNames() {
        Set<String> ret = new LinkedHashSet<>();
        for (PlaintextRole role : getDeclaredRoles()) {
            ret.add(role.authorityName());
        }
        return ret;
    }

    /**
     * Die Beschreibung einer deklarierten Rolle, unabhaengig von Schreibweise/Prefix der Anfrage.
     *
     * @param roleName Rollenname (beliebige Schreibweise, mit oder ohne {@code ROLE_}-Prefix)
     * @return Beschreibung oder leerer String, wenn die Rolle nicht (mehr) deklariert ist
     */
    public String getDescription(String roleName) {
        if (roleName == null || roleName.trim().isEmpty()) {
            return "";
        }
        String normalized = PlaintextRole.of(roleName).normalizedName();
        for (PlaintextRole role : getDeclaredRoles()) {
            if (role.normalizedName().equals(normalized)) {
                return role.description();
            }
        }
        return "";
    }
}

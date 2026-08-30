/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
 * Collects the roles declared by all modules — the role counterpart to the menu registry: as
 * with the menu system, modules contribute as a Spring bean
 * ({@link PlaintextRoleProvider}), and root collects the contributions centrally (union,
 * deduplicated by the {@linkplain PlaintextRole#normalizedName() normalized name}).
 *
 * <p>Consumers (e.g. the user administration) thereby get a complete, described role selection
 * instead of having to type role names freehand. Roles that no module declares (any more) do
 * NOT disappear from the users because of that — the consumers mix in the existing stock (DB)
 * as a fallback.</p>
 *
 * @author info@plaintext.ch
 * @since 1.600.0
 */
@Component
@Slf4j
public class PlaintextRoleRegistry {

    private List<PlaintextRoleProvider> roleProviders;

    /**
     * For contexts without a {@link PlaintextRoleProvider} bean: Spring falls back to this
     * constructor when no providers can be resolved (the registry then stays empty).
     */
    public PlaintextRoleRegistry() {
        this(null);
    }

    /**
     * The constructor Spring prefers: collects all provider beans.
     *
     * @param roleProviders all {@link PlaintextRoleProvider} beans, may be {@code null}
     */
    @Autowired(required = false)
    public PlaintextRoleRegistry(@Nullable List<PlaintextRoleProvider> roleProviders) {
        this.roleProviders = roleProviders;
    }

    /**
     * All declared roles, deduplicated by the normalized name and sorted alphabetically.
     * If several modules declare the same role, the first non-empty description wins.
     *
     * @return declared roles (never {@code null})
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
     * The normalized names of all declared roles (lowercase, without the {@code ROLE_} prefix) —
     * the format in which the user administration stores roles on a user.
     *
     * @return normalized role names, sorted alphabetically
     */
    public Set<String> getDeclaredRoleNames() {
        Set<String> ret = new LinkedHashSet<>();
        for (PlaintextRole role : getDeclaredRoles()) {
            ret.add(role.normalizedName());
        }
        return ret;
    }

    /**
     * The authority names of all declared roles ({@code ROLE_<UPPERCASE>}) — the format of the
     * Spring Security authorities resp. of the role assignment.
     *
     * @return authority names, sorted alphabetically
     */
    public Set<String> getDeclaredAuthorityNames() {
        Set<String> ret = new LinkedHashSet<>();
        for (PlaintextRole role : getDeclaredRoles()) {
            ret.add(role.authorityName());
        }
        return ret;
    }

    /**
     * The description of a declared role, regardless of the spelling/prefix of the request.
     *
     * @param roleName role name (any spelling, with or without the {@code ROLE_} prefix)
     * @return description, or an empty string when the role is not (any longer) declared
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

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import ch.plaintext.boot.menu.ModuleRoleProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Feeds the module roles assigned by configuration
 * ({@code plaintext.menu.module-roles.<modulKey>=<rolle>}) into the
 * {@link PlaintextRoleRegistry} automatically.
 *
 * <p>Without this provider, every application that assigns a role to a module would additionally
 * have to write a {@link PlaintextRoleProvider} of its own — otherwise the role would not be
 * offered for selection in the user administration and could not be assigned to anybody. The
 * configuration alone is now enough.</p>
 *
 * <p>If an app already declares a provider of its own for the same role (e.g. guild for
 * {@code finanzen}), that provider's more detailed description wins: the registry deduplicates by
 * the normalized name and keeps the first non-empty description.</p>
 *
 * @author info@plaintext.ch
 * @since 1.604.0
 */
@Component
public class ModuleRoleDeclarationProvider implements PlaintextRoleProvider {

    private final ModuleRoleProperties moduleRoleProperties;

    /**
     * For contexts without a {@link ModuleRoleProperties} bean: Spring falls back to this
     * constructor when the properties cannot be resolved (no roles are declared then).
     */
    public ModuleRoleDeclarationProvider() {
        this(null);
    }

    /**
     * The constructor Spring prefers; also for tests and programmatic use.
     *
     * @param moduleRoleProperties the configured module-to-role assignment, may be {@code null}
     */
    @Autowired(required = false)
    public ModuleRoleDeclarationProvider(@Nullable ModuleRoleProperties moduleRoleProperties) {
        this.moduleRoleProperties = moduleRoleProperties;
    }

    @Override
    public Set<String> getRoles() {
        Set<String> ret = new LinkedHashSet<>();
        for (PlaintextRole role : getDeclaredRoles()) {
            ret.add(role.name());
        }
        return ret;
    }

    @Override
    public Set<PlaintextRole> getDeclaredRoles() {
        Set<PlaintextRole> ret = new LinkedHashSet<>();
        if (moduleRoleProperties == null) {
            return ret;
        }
        // Role -> module keys, so that a role used by several modules is declared only once (but
        // with a complete description).
        Map<String, List<String>> keysByRole = new TreeMap<>();
        for (Map.Entry<String, String> entry : moduleRoleProperties.canonicalModuleRoles().entrySet()) {
            keysByRole.computeIfAbsent(entry.getValue(), r -> new ArrayList<>()).add(entry.getKey());
        }
        for (Map.Entry<String, List<String>> entry : keysByRole.entrySet()) {
            ret.add(new PlaintextRole(entry.getKey().toLowerCase(java.util.Locale.ROOT),
                    beschreibung(entry.getValue())));
        }
        return ret;
    }

    private String beschreibung(List<String> moduleKeys) {
        if (moduleKeys.size() == 1) {
            return "Zugriff auf das Modul " + moduleKeys.get(0);
        }
        return "Zugriff auf die Module " + String.join(", ", moduleKeys);
    }
}

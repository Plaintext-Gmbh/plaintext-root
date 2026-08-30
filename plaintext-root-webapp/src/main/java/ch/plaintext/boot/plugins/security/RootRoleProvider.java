/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.framework.PlaintextRole;
import ch.plaintext.framework.PlaintextRoleProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reference implementation of the role registry pattern: declares root's own roles so that they
 * appear as a choice in the user administration (instead of being typed in freehand). Apps that
 * use root declare their module roles according to the same pattern —
 * one {@link PlaintextRoleProvider} bean per module.
 *
 * <p>Deliberately NOT declared: {@code system} ({@code ROLE_SYSTEM} is a purely technical
 * authority for cron/bus contexts and must not be assigned to any user) as well as the
 * {@code PROPERTY_*} pseudo roles (tenant etc.), which the user administration handles separately.</p>
 *
 * @author info@plaintext.ch
 * @since 1.600.0
 */
@Component
public class RootRoleProvider implements PlaintextRoleProvider {

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
        ret.add(new PlaintextRole("root", "Superuser: mandantenuebergreifender Vollzugriff inkl. Impersonation"));
        ret.add(new PlaintextRole("admin", "Administration des eigenen Mandanten (Benutzer, Einstellungen, Admin-Panels)"));
        ret.add(new PlaintextRole("user", "Standard-Benutzer: Zugriff auf die freigegebenen Anwendungsseiten"));
        return ret;
    }
}

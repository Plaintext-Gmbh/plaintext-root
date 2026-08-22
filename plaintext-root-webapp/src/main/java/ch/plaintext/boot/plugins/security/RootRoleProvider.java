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
 * Referenzimplementierung des Rollen-Registry-Musters: deklariert die root-eigenen Rollen,
 * damit sie in der Benutzerverwaltung als Auswahl erscheinen (statt freihaendig getippt zu
 * werden). Apps, die root nutzen, deklarieren ihre Modul-Rollen nach demselben Muster —
 * eine {@link PlaintextRoleProvider}-Bean pro Modul.
 *
 * <p>Bewusst NICHT deklariert: {@code system} ({@code ROLE_SYSTEM} ist eine rein technische
 * Authority fuer Cron-/Bus-Kontexte und soll keinem Benutzer zugewiesen werden) sowie die
 * {@code PROPERTY_*}-Pseudo-Rollen (Mandat etc.), die die Benutzerverwaltung separat behandelt.</p>
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

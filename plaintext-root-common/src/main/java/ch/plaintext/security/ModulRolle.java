/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.security;

import ch.plaintext.PlaintextRoles;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.arch.StabileApi;
import ch.plaintext.boot.menu.ModuleRoleProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Modulrolle auch auf REST und MCP (Karte 1500, Review 1450 M2): {@code @PreAuthorize("… and @modulRolle.darf('fotos')")}.
 *
 * <p>Gleiche Regel wie der Page-Guard ({@code ModuleRoleService.holdsAny}): ist dem Modul in
 * {@code plaintext.menu.module-roles} keine Rolle zugeordnet, ist es frei; ADMIN und ROOT dürfen immer;
 * sonst braucht es die konfigurierte Rolle. Die Rolle steht bewusst in der Konfiguration und nicht im
 * Modul: wiki und auszahlungen laufen auch in guild, dort mit anderer Zuordnung ({@code auszahlung}).
 * Darum liegt das Bean in der Hülle (root-common, Karte 1525; vorher app-interfaces) und ist in jeder Instanz da.</p>
 */
@StabileApi("Bean @modulRolle fuer @PreAuthorize der Module (Karte 1500/1525, aus app-interfaces)")
@Component("modulRolle")
public class ModulRolle {

    private final ObjectProvider<ModuleRoleProperties> properties;
    private final ObjectProvider<PlaintextSecurity> security;

    public ModulRolle(ObjectProvider<ModuleRoleProperties> properties, ObjectProvider<PlaintextSecurity> security) {
        this.properties = properties;
        this.security = security;
    }

    /**
     * @param modulKey Modul-Key wie in {@code plaintext.menu.module-roles}, z. B. {@code fotos}
     * @return true, wenn der angemeldete Benutzer das Modul nutzen darf
     */
    public boolean darf(String modulKey) {
        ModuleRoleProperties p = properties.getIfAvailable();
        String rolle = p == null ? null : p.canonicalModuleRoles().get(ModuleRoleProperties.canonicalKey(modulKey));
        if (rolle == null) {
            return true;
        }
        PlaintextSecurity s = security.getIfAvailable();
        return PlaintextRoles.isAdmin(s) || PlaintextRoles.hasAny(s, rolle);
    }
}

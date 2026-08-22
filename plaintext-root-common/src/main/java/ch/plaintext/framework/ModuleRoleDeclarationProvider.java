/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import ch.plaintext.boot.menu.ModuleRoleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Speist die per Konfiguration vergebenen Modul-Rollen
 * ({@code plaintext.menu.module-roles.<modulKey>=<rolle>}) automatisch in die
 * {@link PlaintextRoleRegistry} ein.
 *
 * <p>Ohne diesen Provider muesste jede Anwendung, die einem Modul eine Rolle zuordnet, zusaetzlich
 * einen eigenen {@link PlaintextRoleProvider} schreiben — sonst stuende die Rolle in der
 * Benutzerverwaltung nicht zur Auswahl und koennte niemandem vergeben werden. Die Konfiguration
 * allein genuegt jetzt.</p>
 *
 * <p>Deklariert eine App bereits einen eigenen Provider fuer dieselbe Rolle (z.B. guild fuer
 * {@code finanzen}), gewinnt dessen ausfuehrlichere Beschreibung: die Registry dedupliziert ueber
 * den normalisierten Namen und behaelt die erste nicht-leere Beschreibung.</p>
 *
 * @author info@plaintext.ch
 * @since 1.604.0
 */
@Component
public class ModuleRoleDeclarationProvider implements PlaintextRoleProvider {

    @Autowired(required = false)
    private ModuleRoleProperties moduleRoleProperties;

    /**
     * Fuer den Spring-Betrieb; die Properties kommen per Feld-Injection.
     */
    public ModuleRoleDeclarationProvider() {
        // Spring
    }

    /**
     * Fuer Tests und programmatische Nutzung.
     *
     * @param moduleRoleProperties die konfigurierte Modul-Rollen-Zuordnung, darf {@code null} sein
     */
    public ModuleRoleDeclarationProvider(ModuleRoleProperties moduleRoleProperties) {
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
        // Rolle -> Modul-Keys, damit eine Rolle fuer mehrere Module nur einmal (aber vollstaendig
        // beschrieben) deklariert wird.
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

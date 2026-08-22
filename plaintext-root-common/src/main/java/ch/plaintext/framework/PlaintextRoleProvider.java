/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Modul-Beitrag fuer Rollen — das Pendant zum Menue-Muster ({@code @MenuAnnotation}/Submenu-Beans),
 * mit dem Module Menueeintraege beisteuern: Ein Modul (in root selbst oder in einer App, die root
 * nutzt) implementiert dieses Interface als Spring-Bean und deklariert damit die Rollen, die es
 * kennt/prueft. Die {@link PlaintextRoleRegistry} sammelt alle Provider-Beans ein (Union,
 * dedupliziert) und stellt die Rollen z.B. der Benutzerverwaltung als Auswahl zur Verfuegung.
 *
 * <p>Referenzimplementierung: {@code RootRoleProvider} im Webapp-Modul deklariert die
 * root-eigenen Rollen ({@code root}, {@code admin}, {@code user}, {@code system}).</p>
 *
 * @author info@plaintext.ch
 * @since 0.0.1
 */
public interface PlaintextRoleProvider {

    /**
     * Die technischen Rollennamen dieses Moduls.
     *
     * @return Rollennamen (mit oder ohne {@code ROLE_}-Prefix)
     */
    Set<String> getRoles();

    /**
     * Die Rollen dieses Moduls inklusive Beschreibung fuer Auswahl-UIs.
     *
     * <p>Default: leitet aus {@link #getRoles()} Rollen ohne Beschreibung ab — bestehende
     * Provider bleiben damit unveraendert lauffaehig. Module, die eine Beschreibung anzeigen
     * wollen, ueberschreiben diese Methode (und liefern {@link #getRoles()} konsistent dazu).</p>
     *
     * @return deklarierte Rollen mit Beschreibung
     * @since 1.600.0
     */
    default Set<PlaintextRole> getDeclaredRoles() {
        Set<PlaintextRole> ret = new LinkedHashSet<>();
        for (String role : getRoles()) {
            if (role != null && !role.trim().isEmpty()) {
                ret.add(PlaintextRole.of(role));
            }
        }
        return ret;
    }

}

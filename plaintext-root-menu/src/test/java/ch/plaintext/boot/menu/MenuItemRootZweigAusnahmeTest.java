/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.MenuVisibilityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Root-Zweig ist fuer {@code ROLE_ROOT} vom Mandantenfilter ausgenommen — und nur der, und nur
 * fuer root.
 *
 * <p>Henne-Ei-Problem: die Menuesteuerung selbst haengt im Root-Zweig. Steht ein Mandant im
 * Whitelist-Modus ohne diesen Titel, sperrt sich root aus der einzigen Oberflaeche aus, mit der die
 * Liste zu korrigieren waere — per Menue und (weil der {@code PageAccessGuard} dieselbe
 * {@link MenuItemImpl#isOn()} auswertet) auch per Direkt-URL.</p>
 */
@DisplayName("Root-Zweig-Ausnahme vom Mandantenfilter")
class MenuItemRootZweigAusnahmeTest {

    private static final String MENUESTEUERUNG = "Menüsteuerung";
    private static final String ROOT = "Root";

    /** Blendet ALLES aus — der schaerfste Mandantenfilter, den es gibt. */
    private static final MenuVisibilityProvider ALLES_AUS = new MenuVisibilityProvider() {
        @Override
        public boolean isMenuVisible(String menuTitle) {
            return false;
        }

        @Override
        public boolean isMenuVisibleForMandate(String menuTitle, String mandate) {
            return false;
        }
    };

    private static SecurityProvider security(Set<String> rollen) {
        Set<String> gross = rollen.stream().map(r -> r.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        return rolle -> {
            if (rolle == null) {
                return false;
            }
            String mit = rolle.toUpperCase(Locale.ROOT);
            String ohne = mit.startsWith("ROLE_") ? mit.substring(5) : mit;
            return gross.contains(mit) || gross.contains(ohne);
        };
    }

    private static MenuItemImpl menu(String titel, String parent, Set<String> rollenDesBenutzers) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(titel);
        item.setParent(parent);
        item.setCommand(titel.toLowerCase(Locale.ROOT) + ".html");
        item.setRoles(List.of("ROOT"));
        item.setSecurityProvider(security(rollenDesBenutzers));
        item.setMenuVisibilityProvider(ALLES_AUS);
        return item;
    }

    @Test
    @DisplayName("root sieht die Menuesteuerung, auch wenn der Mandantenfilter alles ausblendet")
    void rootSiehtRootZweigTrotzMandantenfilter() {
        MenuItemImpl item = menu(MENUESTEUERUNG, ROOT, Set.of("ROOT"));

        assertTrue(item.isRootBranchExemptFromMandate(), "Ausnahme muss greifen");
        assertTrue(item.isMandateVisible(), "Mandantenfilter muss uebersprungen werden");
        assertTrue(item.isOn(), "Menuepunkt muss sichtbar sein");
    }

    @Test
    @DisplayName("Das Root-Menue selbst ist ebenfalls ausgenommen")
    void rootMenueSelbstIstAusgenommen() {
        MenuItemImpl item = menu(ROOT, "", Set.of("ROOT"));

        assertTrue(item.isRootBranchExemptFromMandate());
        assertTrue(item.isOn());
    }

    @Test
    @DisplayName("NEGATIV: ein Nicht-root-Benutzer bekommt die Ausnahme nicht")
    void nichtRootBekommtKeineAusnahme() {
        MenuItemImpl item = menu(MENUESTEUERUNG, ROOT, Set.of("ADMIN", "USER"));

        assertFalse(item.isRootBranchExemptFromMandate(), "Ausnahme darf nur fuer ROLE_ROOT greifen");
        assertFalse(item.isMandateVisible(), "Der Mandantenfilter bleibt fuer Nicht-root in Kraft");
        assertFalse(item.isOn());
    }

    @Test
    @DisplayName("NEGATIV: ausserhalb des Root-Zweigs bleibt der Mandantenfilter auch fuer root scharf")
    void ausserhalbDesRootZweigsKeineAusnahmeFuerRoot() {
        MenuItemImpl item = menu("Rechnungen", "Buchhaltung", Set.of("ROOT"));

        assertFalse(item.isRootBranchExemptFromMandate(), "Nur der Root-Zweig ist ausgenommen");
        assertFalse(item.isMandateVisible(), "Eine Rolle hebt den Mandantenfilter nicht auf");
        assertFalse(item.isOn());
    }

    @Test
    @DisplayName("NEGATIV: ohne SecurityProvider gibt es keine Ausnahme")
    void ohneSecurityProviderKeineAusnahme() {
        MenuItemImpl item = menu(MENUESTEUERUNG, ROOT, Set.of("ROOT"));
        item.setSecurityProvider(null);

        assertFalse(item.isRootBranchExemptFromMandate());
        assertFalse(item.isMandateVisible());
    }

    @Test
    @DisplayName("Die Ausnahme betrifft NUR den Mandantenfilter — die drei anderen bleiben scharf")
    void andereFilterBleibenScharf() {
        MenuItemImpl item = menu(MENUESTEUERUNG, ROOT, Set.of("ROOT"));
        item.setModuleId("menuesteuerung");
        item.setModuleEnablementProvider(modul -> false);

        assertTrue(item.isMandateVisible(), "Mandantenfilter ist ausgenommen");
        assertFalse(item.isModuleVisible(), "das deaktivierte Modul bleibt deaktiviert");
        assertFalse(item.isOn(), "und damit ist der Menuepunkt trotzdem weg");
    }

    @Test
    @DisplayName("Ohne Ausnahme werden die Modul-Keys an den Provider durchgereicht")
    void modulKeysWerdenDurchgereicht() {
        StringBuilder gesehen = new StringBuilder();
        MenuItemImpl item = menu("Rechnungen", "Buchhaltung", Set.of("ROOT"));
        item.setModuleKeys(List.of("rechnungen"));
        item.setMenuVisibilityProvider(new MenuVisibilityProvider() {
            @Override
            public boolean isMenuVisible(String menuTitle) {
                gesehen.append("ohne-keys");
                return true;
            }

            @Override
            public boolean isMenuVisibleForMandate(String menuTitle, String mandate) {
                return true;
            }

            @Override
            public boolean isMenuVisible(String menuTitle, Collection<String> moduleKeys) {
                gesehen.append(moduleKeys);
                return true;
            }
        });

        assertTrue(item.isMandateVisible());
        assertEquals("[rechnungen]", gesehen.toString());
    }

    @Test
    @DisplayName("Ohne Modul-Keys wird die alte Ein-Argument-Form gerufen (Rueckwaertskompatibilitaet)")
    void ohneModulKeysAlteForm() {
        StringBuilder gesehen = new StringBuilder();
        MenuItemImpl item = menu("Rechnungen", "Buchhaltung", Set.of("ROOT"));
        item.setModuleKeys(List.of());
        item.setMenuVisibilityProvider(new MenuVisibilityProvider() {
            @Override
            public boolean isMenuVisible(String menuTitle) {
                gesehen.append("ohne-keys");
                return true;
            }

            @Override
            public boolean isMenuVisibleForMandate(String menuTitle, String mandate) {
                return true;
            }
        });

        assertTrue(item.isMandateVisible());
        assertEquals("ohne-keys", gesehen.toString());
    }
}

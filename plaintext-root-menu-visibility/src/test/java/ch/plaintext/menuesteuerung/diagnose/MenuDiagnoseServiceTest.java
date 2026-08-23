/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.diagnose;

import ch.plaintext.MenuRegistry;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.SecurityProvider;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import ch.plaintext.menuesteuerung.service.MandateMenuVisibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * Die Diagnose-Auswertung: vier Filter einzeln, zu jedem Nein ein Grund.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Menue-Diagnose")
class MenuDiagnoseServiceTest {

    private static final String MANDANT = "lauftage2026";

    @Mock
    private MandateMenuConfigRepository repository;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @Mock
    private MenuRegistry menuRegistry;

    private MandateMenuVisibilityService visibilityService;
    private MenuDiagnoseService diagnose;

    @BeforeEach
    void setUp() {
        visibilityService = new MandateMenuVisibilityService(repository, plaintextSecurity, menuRegistry);
        diagnose = new MenuDiagnoseService(visibilityService);
    }

    private void mandantHat(boolean whitelist, String... eintraege) {
        MandateMenuConfig config = new MandateMenuConfig();
        config.setMandateName(MANDANT);
        config.setWhitelistMode(whitelist);
        config.setHiddenMenus(new HashSet<>(Set.of(eintraege)));
        lenient().when(repository.findByMandateName(MANDANT)).thenReturn(Optional.of(config));
        // Der Menuepunkt fragt den Provider ohne Mandanten-Argument — der nimmt den Mandanten der
        // Session. Genau so laeuft es im Betrieb, und genau deshalb zeigt die Diagnose im
        // Impersonate-Modus die Sicht des impersonierten Benutzers.
        lenient().when(plaintextSecurity.getMandat()).thenReturn(MANDANT);
    }

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

    private MenuItemImpl menu(String titel, String parent, Set<String> rollenDesBenutzers) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(titel);
        item.setParent(parent);
        item.setCommand(titel.toLowerCase(Locale.ROOT).replace(' ', '-') + ".html");
        item.setRoles(List.of("USER", "ADMIN", "ROOT"));
        item.setSecurityProvider(security(rollenDesBenutzers));
        item.setMenuVisibilityProvider(visibilityService);
        return item;
    }

    @Test
    @DisplayName("Alles Ja: der Menuepunkt ist sichtbar, kein Grund")
    void allesJa() {
        mandantHat(false);
        MenuItemImpl item = menu("Wiki", "", Set.of("USER"));
        item.setModuleKeys(List.of("wiki"));

        MenuDiagnoseZeile zeile = diagnose.analysiere(item, MANDANT);

        assertTrue(zeile.rolleOk());
        assertTrue(zeile.modulRolleOk());
        assertTrue(zeile.modulOk());
        assertTrue(zeile.mandantOk());
        assertTrue(zeile.sichtbar());
        assertEquals("", zeile.getErsterGrund());
    }

    @Test
    @DisplayName("Fehlende Rolle: Nein in Spalte 1 mit den verlangten Rollen im Grund")
    void fehlendeRolle() {
        mandantHat(false);
        MenuItemImpl item = menu("Wiki", "", Set.of("GAST"));

        MenuDiagnoseZeile zeile = diagnose.analysiere(item, MANDANT);

        assertFalse(zeile.rolleOk());
        assertFalse(zeile.sichtbar());
        assertTrue(zeile.rolleGrund().contains("USER"), zeile.rolleGrund());
        assertTrue(zeile.rolleGrund().contains("ADMIN"), zeile.rolleGrund());
        assertEquals(zeile.rolleGrund(), zeile.getErsterGrund());
    }

    @Test
    @DisplayName("Fehlende Modul-Rolle: der Grund nennt die Rolle und die Konfigurations-Quelle")
    void fehlendeModulRolle() {
        mandantHat(false);
        MenuItemImpl item = menu("Wiki", "", Set.of("USER"));
        item.setModuleRoles(List.of("WIKI"));

        MenuDiagnoseZeile zeile = diagnose.analysiere(item, MANDANT);

        assertTrue(zeile.rolleOk());
        assertFalse(zeile.modulRolleOk());
        assertTrue(zeile.modulRolleGrund().contains("WIKI"), zeile.modulRolleGrund());
        assertTrue(zeile.modulRolleGrund().contains("module-roles"), zeile.modulRolleGrund());
    }

    @Test
    @DisplayName("Deaktiviertes Modul: der Grund nennt die moduleId")
    void deaktiviertesModul() {
        mandantHat(false);
        MenuItemImpl item = menu("Wiki", "", Set.of("USER"));
        item.setModuleId("wiki");
        item.setModuleEnablementProvider(modul -> false);

        MenuDiagnoseZeile zeile = diagnose.analysiere(item, MANDANT);

        assertFalse(zeile.modulOk());
        assertTrue(zeile.modulGrund().contains("wiki"), zeile.modulGrund());
    }

    @Test
    @DisplayName("Whitelist: der Grund nennt Modus und Mandant")
    void nichtInWhitelist() {
        mandantHat(true, "Kontakte");
        MenuItemImpl item = menu("Wiki", "", Set.of("USER"));
        item.setModuleKeys(List.of("wiki"));

        MenuDiagnoseZeile zeile = diagnose.analysiere(item, MANDANT);

        assertTrue(zeile.rolleOk());
        assertFalse(zeile.mandantOk());
        assertEquals("nicht in Whitelist von " + MANDANT, zeile.mandantGrund());
        assertEquals("nicht in Whitelist von " + MANDANT, zeile.getErsterGrund());
    }

    @Test
    @DisplayName("Blacklist ueber einen Modul-Eintrag: auch das Untermenue bekommt den Grund")
    void blacklistUeberModulEintrag() {
        mandantHat(false, "modul:wiki");
        MenuItemImpl item = menu("Projekte", "Wiki", Set.of("USER"));
        item.setModuleKeys(List.of("wiki"));

        MenuDiagnoseZeile zeile = diagnose.analysiere(item, MANDANT);

        assertFalse(zeile.mandantOk());
        assertEquals("in Blacklist von " + MANDANT, zeile.mandantGrund());
    }

    @Test
    @DisplayName("Root-Zweig: der Hinweis erklaert, warum die Liste hier nicht entschieden hat")
    void rootZweigHinweis() {
        mandantHat(true, "Kontakte");
        MenuItemImpl item = menu("Menüsteuerung", "Root", Set.of("ROOT"));

        MenuDiagnoseZeile zeile = diagnose.analysiere(item, MANDANT);

        assertTrue(zeile.mandantOk());
        assertTrue(zeile.sichtbar());
        assertTrue(zeile.mandantGrund().contains("Root-Zweig"), zeile.mandantGrund());
    }

    @Test
    @DisplayName("Mehrere Neins: der erste Grund ist der der Reihenfolge nach erste Filter")
    void ersterGrundGewinnt() {
        mandantHat(true, "Kontakte");
        MenuItemImpl item = menu("Wiki", "", Set.of("GAST"));

        MenuDiagnoseZeile zeile = diagnose.analysiere(item, MANDANT);

        assertFalse(zeile.rolleOk());
        assertFalse(zeile.mandantOk());
        assertEquals(zeile.rolleGrund(), zeile.getErsterGrund());
    }

    @Test
    @DisplayName("Die Liste ist alphabetisch nach vollem Titel sortiert")
    void sortierung() {
        mandantHat(false);
        List<MenuDiagnoseZeile> zeilen = diagnose.analysiereAlle(List.of(
                menu("Zeiterfassung", "", Set.of("USER")),
                menu("Adressen", "", Set.of("USER")),
                menu("Projekte", "Wiki", Set.of("USER"))), MANDANT);

        assertEquals(List.of("Adressen", "Wiki | Projekte", "Zeiterfassung"),
                zeilen.stream().map(MenuDiagnoseZeile::titel).toList());
    }

    @Test
    @DisplayName("Modul-Keys erscheinen als Text, leer als Gedankenstrich")
    void modulKeysText() {
        mandantHat(false);
        MenuItemImpl mitKeys = menu("Wiki", "", Set.of("USER"));
        mitKeys.setModuleKeys(List.of("wiki", "wissen"));
        MenuItemImpl ohneKeys = menu("Adressen", "", Set.of("USER"));

        assertEquals("wiki, wissen", diagnose.analysiere(mitKeys, MANDANT).getModulKeysText());
        assertEquals("—", diagnose.analysiere(ohneKeys, MANDANT).getModulKeysText());
    }

    @Test
    @DisplayName("Robust: null-Eingabe liefert eine leere Liste")
    void robust() {
        assertTrue(diagnose.analysiereAlle(null, MANDANT).isEmpty());
    }
}

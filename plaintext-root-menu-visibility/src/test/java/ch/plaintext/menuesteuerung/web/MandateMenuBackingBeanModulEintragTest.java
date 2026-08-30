/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.ModuleRoleProperties;
import ch.plaintext.boot.menu.ModuleRoleService;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.service.MandateMenuVisibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * The detail page splits the stored list into three selections (modules, titles, dead entries) and
 * reassembles them when saving.
 *
 * <p>The most important test here is {@link #toteEintraegeUeberlebenDasSpeichern()}: the migration
 * must lose nothing, not even an entry the current menu tree no longer knows about.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Menuesteuerung-Detailseite: Modul- und Titel-Eintraege")
class MandateMenuBackingBeanModulEintragTest {

    private static final String WIKI_MODUL = "modul:wiki";
    private static final String TITEL = "Kontakte | Liste";
    private static final String TOT = "Alter Menuepunkt";

    @Mock
    private MandateMenuVisibilityService service;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    private MandateMenuBackingBean bean;

    @BeforeEach
    void setUp() {
        bean = new MandateMenuBackingBean();
        ReflectionTestUtils.setField(bean, "service", service);
        ReflectionTestUtils.setField(bean, "plaintextSecurity", plaintextSecurity);
        ReflectionTestUtils.setField(bean, "moduleRoleService", moduleRoleService("wiki", "kontakte"));
        lenient().when(service.getAllMenuTitles()).thenReturn(List.of("Wiki", "Kontakte | Liste", "Kontakte"));
    }

    private static ModuleRoleService moduleRoleService(String... moduleIds) {
        List<MenuItemImpl> baum = new ArrayList<>();
        for (String moduleId : moduleIds) {
            MenuItemImpl item = new MenuItemImpl();
            item.setTitle(moduleId);
            item.setParent("");
            item.setModuleId(moduleId);
            baum.add(item);
        }
        ModuleRoleService moduleRoleService = new ModuleRoleService(null, new ModuleRoleProperties());
        moduleRoleService.resolve(baum);
        return moduleRoleService;
    }

    private void gespeichert(String... eintraege) {
        MandateMenuConfig config = new MandateMenuConfig();
        config.setMandateName("testmandant");
        config.setHiddenMenus(new HashSet<>(Set.of(eintraege)));
        bean.setSelected(config);
        bean.initDetail();
    }

    @Test
    @DisplayName("Die waehlbaren Module kommen aus den erkannten Modul-Keys")
    void waehlbareModule() {
        gespeichert();

        assertEquals(List.of("modul:kontakte", WIKI_MODUL), bean.getAvailableModuleEntries());
    }

    @Test
    @DisplayName("Die gespeicherte Liste wird in drei Gruppen zerlegt")
    void dreiGruppen() {
        gespeichert(WIKI_MODUL, TITEL, TOT);

        assertEquals(List.of(WIKI_MODUL), bean.getSelectedModuleEntries());
        assertEquals(List.of(TITEL), bean.getSelectedTitleEntries());
        assertEquals(List.of(TOT), bean.getDeadEntries());
    }

    @Test
    @DisplayName("VERLUSTFREI: tote Eintraege sind vorausgewaehlt und ueberleben das Speichern")
    void toteEintraegeUeberlebenDasSpeichern() {
        gespeichert(WIKI_MODUL, TITEL, TOT);

        assertEquals(List.of(TOT), bean.getSelectedDeadEntries(),
                "Tote Eintraege muessen vorausgewaehlt sein, sonst wirft das naechste Speichern sie weg");
        assertEquals(Set.of(WIKI_MODUL, TITEL, TOT), bean.zusammengefuehrteAuswahl());
    }

    @Test
    @DisplayName("Ein abgewaehlter toter Eintrag verschwindet")
    void abgewaehlterToterEintragVerschwindet() {
        gespeichert(WIKI_MODUL, TOT);
        bean.setSelectedDeadEntries(new ArrayList<>());

        assertEquals(Set.of(WIKI_MODUL), bean.zusammengefuehrteAuswahl());
    }

    @Test
    @DisplayName("Ein Modul-Eintrag in abweichender Schreibweise wird kanonisiert")
    void kanonisierung() {
        gespeichert("MODUL:WIKI");

        assertEquals(List.of(WIKI_MODUL), bean.getSelectedModuleEntries());
        assertTrue(bean.getDeadEntries().isEmpty());
    }

    @Test
    @DisplayName("Alle an waehlt Module UND Titel")
    void alleAn() {
        gespeichert();

        bean.selectAll();

        assertEquals(Set.of(WIKI_MODUL, "modul:kontakte", "Wiki", "Kontakte | Liste", "Kontakte"),
                bean.getSelected().getHiddenMenus());
        assertEquals(2, bean.getSelectedModuleEntries().size());
        assertEquals(3, bean.getSelectedTitleEntries().size());
    }

    @Test
    @DisplayName("Modus-Umschalten invertiert Module und Titel gemeinsam")
    void modusUmschalten() {
        gespeichert(WIKI_MODUL, "Wiki");

        bean.toggleMode();

        assertEquals(Set.of("modul:kontakte", "Kontakte | Liste", "Kontakte"),
                bean.getSelected().getHiddenMenus());
        assertTrue(Boolean.TRUE.equals(bean.getSelected().getWhitelistMode()));
    }
}

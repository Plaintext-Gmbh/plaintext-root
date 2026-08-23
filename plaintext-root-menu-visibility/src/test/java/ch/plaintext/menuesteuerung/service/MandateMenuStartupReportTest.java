/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.service;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.ModuleRoleProperties;
import ch.plaintext.boot.menu.ModuleRoleService;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Der Start meldet pro Mandant, welche Listen-Eintraege im aktuellen Menuebaum ins Leere zeigen —
 * Vorbild ist die bestehende Meldung „Modul-Rolle konfiguriert fuer unbekannten Modul-Key".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Startup-Meldung: tote Listen-Eintraege")
class MandateMenuStartupReportTest {

    @Mock
    private MandateMenuConfigRepository repository;

    @Mock
    private MenuRegistry menuRegistry;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(MandateMenuStartupReport.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
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
        ModuleRoleService service = new ModuleRoleService(null, new ModuleRoleProperties());
        service.resolve(baum);
        return service;
    }

    private static MandateMenuConfig config(String name, boolean whitelist, String... eintraege) {
        MandateMenuConfig config = new MandateMenuConfig();
        config.setMandateName(name);
        config.setWhitelistMode(whitelist);
        config.setHiddenMenus(new HashSet<>(Set.of(eintraege)));
        return config;
    }

    private List<String> warnungen() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("Ein toter Titel wird pro Mandant mit Namen gemeldet")
    void toterTitelWirdGemeldet() {
        when(repository.findAll()).thenReturn(List.of(
                config("lauftage2026", true, "Wiki | Projekte", "Wiki | Alter Name")));
        when(menuRegistry.getAllMenuTitles()).thenReturn(List.of("Wiki | Projekte", "Kontakte"));

        new MandateMenuStartupReport(repository, menuRegistry, moduleRoleService("wiki")).berichte();

        List<String> warnungen = warnungen();
        assertTrue(warnungen.stream().anyMatch(m -> m.contains("lauftage2026")
                        && m.contains("Wiki | Alter Name")
                        && m.contains("Whitelist")),
                "Erwartet: Mandant, Modus und toter Eintrag in der Meldung. Tatsaechlich: " + warnungen);
    }

    @Test
    @DisplayName("Ein Modul-Eintrag auf einen bekannten Key gilt NICHT als tot")
    void bekannterModulKeyIstNichtTot() {
        when(repository.findAll()).thenReturn(List.of(config("butscher", false, "modul:wiki")));
        when(menuRegistry.getAllMenuTitles()).thenReturn(List.of("Wiki"));

        new MandateMenuStartupReport(repository, menuRegistry, moduleRoleService("wiki")).berichte();

        assertTrue(warnungen().isEmpty(), "Erwartet keine Warnung, war: " + warnungen());
    }

    @Test
    @DisplayName("Ein Modul-Eintrag auf einen unbekannten Key wird gemeldet")
    void unbekannterModulKeyWirdGemeldet() {
        when(repository.findAll()).thenReturn(List.of(config("butscher", false, "modul:gibtsnicht")));
        when(menuRegistry.getAllMenuTitles()).thenReturn(List.of("Wiki"));

        new MandateMenuStartupReport(repository, menuRegistry, moduleRoleService("wiki")).berichte();

        assertTrue(warnungen().stream().anyMatch(m -> m.contains("modul:gibtsnicht")));
    }

    @Test
    @DisplayName("Mehrere Mandanten: nur die betroffenen werden gemeldet")
    void nurBetroffeneMandanten() {
        when(repository.findAll()).thenReturn(List.of(
                config("sauber", false, "Wiki"),
                config("kaputt", false, "Nicht mehr da")));
        when(menuRegistry.getAllMenuTitles()).thenReturn(List.of("Wiki"));

        new MandateMenuStartupReport(repository, menuRegistry, moduleRoleService("wiki")).berichte();

        List<String> warnungen = warnungen();
        assertTrue(warnungen.stream().anyMatch(m -> m.contains("kaputt")));
        assertFalse(warnungen.stream().anyMatch(m -> m.contains("Mandant 'sauber'")));
    }

    @Test
    @DisplayName("Ohne Konfiguration passiert nichts")
    void ohneKonfiguration() {
        when(repository.findAll()).thenReturn(List.of());

        new MandateMenuStartupReport(repository, menuRegistry, null).berichte();

        assertTrue(warnungen().isEmpty());
    }

    @Test
    @DisplayName("Ein Fehler beim Lesen gefaehrdet den Start nicht")
    void fehlerBrichtDenStartNicht() {
        lenient().when(repository.findAll()).thenThrow(new IllegalStateException("DB weg"));

        assertDoesNotThrow(() -> new MandateMenuStartupReport(repository, menuRegistry, null).berichte());
        assertTrue(warnungen().stream().anyMatch(m -> m.contains("nicht geprueft")));
    }
}

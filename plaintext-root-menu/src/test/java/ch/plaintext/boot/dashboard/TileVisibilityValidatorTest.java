/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.dashboard.TileVisibilityValidator.IssueReason;
import ch.plaintext.boot.dashboard.TileVisibilityValidator.TileVisibilityIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests für {@link TileVisibilityValidator}: Eine Kachel, deren {@code menuTitle} nicht exakt einem
 * registrierten Menü-Titel entspricht (oder fehlt), muss als Konfig-Fehler erkennbar sein, damit
 * das fail-open-Leck der Pro-Mandant-Sichtbarkeit nicht still durchrutscht.
 *
 * @author plaintext.ch
 */
@ExtendWith(MockitoExtension.class)
class TileVisibilityValidatorTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private MenuRegistry menuRegistry;

    private TileVisibilityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TileVisibilityValidator(applicationContext);
    }

    private TileItemImpl tile(String id, String title, String menuTitle) {
        TileItemImpl t = new TileItemImpl();
        t.setId(id);
        t.setTitle(title);
        t.setMenuTitle(menuTitle);
        return t;
    }

    private void givenTiles(TileItemImpl... tiles) {
        Map<String, TileItemImpl> beans = new LinkedHashMap<>();
        int i = 0;
        for (TileItemImpl t : tiles) {
            beans.put("tile" + (i++), t);
        }
        when(applicationContext.getBeansOfType(TileItemImpl.class)).thenReturn(beans);
    }

    private void givenMenuTitles(String... titles) {
        when(applicationContext.getBean(MenuRegistry.class)).thenReturn(menuRegistry);
        when(menuRegistry.getAllMenuTitles()).thenReturn(List.of(titles));
    }

    @Test
    void shouldReportNoIssueWhenMenuTitleMatchesRegisteredMenu() {
        givenTiles(tile("lauftage", "Lauftage", "Lauftage"));
        givenMenuTitles("Lauftage", "Root | Mandate");

        assertTrue(validator.validate().isEmpty(),
            "Exakt passender menuTitle darf kein Problem melden");
    }

    @Test
    void shouldReportNoIssueForHierarchicalTitleMatch() {
        givenTiles(tile("mandate", "Mandate", "Root | Mandate"));
        givenMenuTitles("Lauftage", "Root | Mandate");

        assertTrue(validator.validate().isEmpty());
    }

    @Test
    void shouldDetectMismatchAsConfigError() {
        // Tippfehler/falscher Titel: Kachel würde im Blacklist-Standard fail-open sichtbar bleiben
        givenTiles(tile("lauftage", "Lauftage", "Lauftagee"));
        givenMenuTitles("Lauftage", "Root | Mandate");

        List<TileVisibilityIssue> issues = validator.validate();

        assertEquals(1, issues.size());
        assertEquals("lauftage", issues.get(0).tileId());
        assertEquals("Lauftagee", issues.get(0).visibilityTitle());
        assertEquals(IssueReason.NO_MATCHING_MENU, issues.get(0).reason());
    }

    @Test
    void shouldDetectMissingMenuTitle() {
        // Kein menuTitle gesetzt -> Fallback auf title, der hier kein Menü ist
        givenTiles(tile("orphan", "Irgendeine Kachel", ""));
        givenMenuTitles("Lauftage");

        List<TileVisibilityIssue> issues = validator.validate();

        assertEquals(1, issues.size());
        assertEquals(IssueReason.MISSING_MENU_TITLE, issues.get(0).reason());
    }

    @Test
    void shouldFlagMissingMenuTitleEvenWhenTitleMatchesMenu() {
        // Selbst wenn der title zufällig einem Menü entspricht: menuTitle ist faktisch verpflichtend
        givenTiles(tile("lauftage", "Lauftage", null));
        givenMenuTitles("Lauftage");

        List<TileVisibilityIssue> issues = validator.validate();

        assertEquals(1, issues.size());
        assertEquals(IssueReason.MISSING_MENU_TITLE, issues.get(0).reason());
    }

    @Test
    void shouldReportNoIssueWhenNoTiles() {
        when(applicationContext.getBeansOfType(TileItemImpl.class)).thenReturn(Map.of());
        assertTrue(validator.validate().isEmpty());
    }

    @Test
    void shouldSkipMenuMatchingWhenNoMenuRegistry() {
        // Ohne MenuRegistry kann nicht gegen Menü-Titel abgeglichen werden -> kein NO_MATCHING,
        // aber fehlender menuTitle bleibt erkennbar.
        givenTiles(tile("withTitle", "Kachel", "IrgendEinTitel"));
        when(applicationContext.getBean(MenuRegistry.class))
            .thenThrow(new NoSuchBeanDefinitionException(MenuRegistry.class));

        assertTrue(validator.validate().isEmpty(),
            "Ohne MenuRegistry darf ein gesetzter menuTitle nicht fälschlich als Mismatch gemeldet werden");
    }

    @Test
    void shouldStillDetectMissingMenuTitleWithoutRegistry() {
        givenTiles(tile("orphan", "Kachel", ""));
        when(applicationContext.getBean(MenuRegistry.class))
            .thenThrow(new NoSuchBeanDefinitionException(MenuRegistry.class));

        List<TileVisibilityIssue> issues = validator.validate();

        assertEquals(1, issues.size());
        assertEquals(IssueReason.MISSING_MENU_TITLE, issues.get(0).reason());
    }

    @Test
    void afterSingletonsInstantiatedShouldNotThrow() {
        givenTiles(tile("lauftage", "Lauftage", "Lauftage"));
        givenMenuTitles("Lauftage");

        assertDoesNotThrow(() -> validator.afterSingletonsInstantiated());
    }
}

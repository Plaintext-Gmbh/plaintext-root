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
 * Tests for {@link TileVisibilityValidator}: a tile whose {@code menuTitle} does not match a
 * registered menu title exactly (or is missing) must be recognisable as a configuration error, so
 * that the fail-open leak of the per-tenant visibility does not slip through silently.
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
        // Typo/wrong title: with the blacklist default the tile would stay visible fail-open
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
        // No menuTitle set -> fallback to title, which here is not a menu
        givenTiles(tile("orphan", "Irgendeine Kachel", ""));
        givenMenuTitles("Lauftage");

        List<TileVisibilityIssue> issues = validator.validate();

        assertEquals(1, issues.size());
        assertEquals(IssueReason.MISSING_MENU_TITLE, issues.get(0).reason());
    }

    @Test
    void shouldFlagMissingMenuTitleEvenWhenTitleMatchesMenu() {
        // Even if the title happens to match a menu: menuTitle is effectively mandatory
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
        // Without a MenuRegistry there is nothing to match menu titles against -> no NO_MATCHING,
        // but a missing menuTitle stays detectable.
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

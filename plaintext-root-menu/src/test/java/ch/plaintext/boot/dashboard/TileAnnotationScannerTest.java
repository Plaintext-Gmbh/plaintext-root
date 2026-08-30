/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.MenuVisibilityProvider;
import ch.plaintext.boot.menu.SecurityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TileAnnotationScannerTest {

    @Mock
    private SecurityProvider securityProvider;

    @Mock
    private MenuVisibilityProvider menuVisibilityProvider;

    private TileAnnotationScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new TileAnnotationScanner(securityProvider, menuVisibilityProvider, null);
    }

    @Test
    void shouldFindAnnotatedTileInPackage() {
        List<TileItemImpl> result = scanner.findAnnotatedClasses("ch.plaintext.boot.dashboard");

        assertNotNull(result);
        assertTrue(result.stream().anyMatch(t -> "test-tile".equals(t.getId())),
            "Test-Kachel sollte gefunden werden");
        TileItemImpl found = result.stream()
            .filter(t -> "test-tile".equals(t.getId()))
            .findFirst().orElseThrow();
        assertEquals("Test Kachel", found.getTitle());
        assertEquals("pi pi-test", found.getIcon());
        assertEquals(42, found.getOrder());
        assertEquals("Test Menu", found.getMenuTitle());
        assertEquals(List.of("USER"), found.getRoles());
    }

    @Test
    void shouldReturnEmptyForNonExistentPackage() {
        List<TileItemImpl> result = scanner.findAnnotatedClasses("com.nonexistent.package");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleNullPackageGracefully() {
        assertDoesNotThrow(() -> scanner.findAnnotatedClasses(null));
        assertTrue(scanner.findAnnotatedClasses(null).isEmpty());
    }
}

/**
 * Test tile with {@link DashboardTile} for testing purposes.
 */
@DashboardTile(
    id = "test-tile",
    title = "Test Kachel",
    icon = "pi pi-test",
    order = 42,
    menuTitle = "Test Menu",
    roles = {"USER"}
)
class TestAnnotatedTile {
}

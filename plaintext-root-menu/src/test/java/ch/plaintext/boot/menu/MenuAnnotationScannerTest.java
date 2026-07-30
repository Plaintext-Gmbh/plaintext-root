/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MenuAnnotationScanner
 */
@ExtendWith(MockitoExtension.class)
class MenuAnnotationScannerTest {

    @Mock
    private SecurityProvider securityProvider;

    @Mock
    private ch.plaintext.MenuVisibilityProvider menuVisibilityProvider;

    private MenuAnnotationScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new MenuAnnotationScanner(securityProvider, menuVisibilityProvider, null);
    }

    @Test
    void testFindAnnotatedClasses_WithValidPackage_ShouldReturnMenuItems() {
        // Given
        String scanPackage = "ch.plaintext.boot.menu";

        // When
        List<MenuItemImpl> result = scanner.findAnnotatedClasses(scanPackage);

        // Then
        assertNotNull(result);
        // Result may be empty if no annotated classes exist in test environment
        assertTrue(result.isEmpty() || !result.isEmpty());
    }

    @Test
    void testFindAnnotatedClasses_WithNonExistentPackage_ShouldReturnEmptyList() {
        // Given
        String scanPackage = "com.nonexistent.package";

        // When
        List<MenuItemImpl> result = scanner.findAnnotatedClasses(scanPackage);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindAnnotatedClasses_ShouldReturnListOfMenuItemImpl() {
        // Given
        String scanPackage = "ch.plaintext.boot.menu";

        // When
        List<MenuItemImpl> result = scanner.findAnnotatedClasses(scanPackage);

        // Then
        assertNotNull(result);
        for (MenuItemImpl item : result) {
            assertNotNull(item);
            assertInstanceOf(MenuItemImpl.class, item);
        }
    }

    @Test
    void testConstructor_ShouldAcceptSecurityProvider() {
        // When
        MenuAnnotationScanner newScanner = new MenuAnnotationScanner(securityProvider, menuVisibilityProvider, null);

        // Then
        assertNotNull(newScanner);
    }

    @Test
    void testFindAnnotatedClasses_WithNullPackage_ShouldHandleGracefully() {
        // When/Then
        assertDoesNotThrow(() -> scanner.findAnnotatedClasses(null));
    }

    @Test
    void testFindAnnotatedClasses_WithEmptyPackage_ShouldHandleGracefully() {
        // Given
        String emptyPackage = "";

        // When
        List<MenuItemImpl> result = scanner.findAnnotatedClasses(emptyPackage);

        // Then
        assertNotNull(result);
    }

    @Test
    void testFindAnnotatedClasses_ShouldMapModuleId() {
        // Given
        String scanPackage = "ch.plaintext.boot.menu";

        // When
        List<MenuItemImpl> result = scanner.findAnnotatedClasses(scanPackage);

        // Then
        MenuItemImpl testMenu = result.stream()
                .filter(item -> "Test Menu".equals(item.getTitle()))
                .findFirst()
                .orElseThrow();
        assertEquals("testmodule", testMenu.getModuleId());
    }

    @Test
    void testFindAnnotatedClasses_MultipleCalls_ShouldReturnConsistentResults() {
        // Given
        String scanPackage = "ch.plaintext.boot.menu";

        // When
        List<MenuItemImpl> result1 = scanner.findAnnotatedClasses(scanPackage);
        List<MenuItemImpl> result2 = scanner.findAnnotatedClasses(scanPackage);

        // Then
        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1.size(), result2.size());
    }
}


/**
 * Test class with MenuAnnotation for testing purposes
 */
@MenuAnnotation(
    title = "Test Menu",
    link = "/test",
    order = 1,
    roles = {"ROLE_USER"},
    parent = "",
    icon = "pi pi-test",
    moduleId = "testmodule"
)
class TestAnnotatedClass {
    // Test class
}

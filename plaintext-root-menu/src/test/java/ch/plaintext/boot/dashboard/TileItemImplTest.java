/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.MenuVisibilityProvider;
import ch.plaintext.boot.menu.SecurityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.BeanFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TileItemImplTest {

    private TileItemImpl tile;

    @Mock
    private SecurityProvider securityProvider;

    @Mock
    private MenuVisibilityProvider menuVisibilityProvider;

    @Mock
    private BeanFactory beanFactory;

    @BeforeEach
    void setUp() {
        tile = new TileItemImpl();
    }

    @Nested
    class VisibilityTitle {

        @Test
        void shouldUseTitleWhenMenuTitleEmpty() {
            tile.setTitle("Lauftage");
            tile.setMenuTitle("");
            assertEquals("Lauftage", tile.getVisibilityTitle());
        }

        @Test
        void shouldUseMenuTitleWhenSet() {
            tile.setTitle("Kachel");
            tile.setMenuTitle("Root | Mandate");
            assertEquals("Root | Mandate", tile.getVisibilityTitle());
        }
    }

    @Nested
    class IsOn {

        @Test
        void shouldReturnTrueWhenNoRolesAndNoVisibilityProvider() {
            assertTrue(tile.isOn());
        }

        @Test
        void shouldReturnTrueWhenSecurityProviderNull() {
            tile.setRoles(List.of("ADMIN"));
            tile.setSecurityProvider(null);
            assertTrue(tile.isOn());
        }

        @Test
        void shouldReturnTrueWhenUserHasRole() {
            tile.setRoles(List.of("admin"));
            tile.setSecurityProvider(securityProvider);
            when(securityProvider.hasRole("ADMIN")).thenReturn(true);
            assertTrue(tile.isOn());
        }

        @Test
        void shouldReturnFalseWhenUserLacksAllRoles() {
            tile.setRoles(List.of("admin", "manager"));
            tile.setSecurityProvider(securityProvider);
            when(securityProvider.hasRole(any())).thenReturn(false);
            assertFalse(tile.isOn());
        }

        @Test
        void shouldCheckRolesBeforeVisibilityProvider() {
            tile.setRoles(List.of("admin"));
            tile.setSecurityProvider(securityProvider);
            tile.setMenuVisibilityProvider(menuVisibilityProvider);
            when(securityProvider.hasRole("ADMIN")).thenReturn(false);

            assertFalse(tile.isOn());
            verifyNoInteractions(menuVisibilityProvider);
        }

        @Test
        void shouldDelegateToVisibilityProviderWithMenuTitle() {
            tile.setTitle("Lauftage");
            tile.setMenuTitle("Lauftage");
            tile.setMenuVisibilityProvider(menuVisibilityProvider);
            when(menuVisibilityProvider.isMenuVisible("Lauftage")).thenReturn(true);

            assertTrue(tile.isOn());
            verify(menuVisibilityProvider).isMenuVisible("Lauftage");
        }

        @Test
        void shouldReturnFalseWhenVisibilityProviderHidesTile() {
            tile.setTitle("Lauftage");
            tile.setMenuVisibilityProvider(menuVisibilityProvider);
            when(menuVisibilityProvider.isMenuVisible("Lauftage")).thenReturn(false);

            assertFalse(tile.isOn());
        }

        @Test
        void shouldLazyLoadVisibilityProviderFromBeanFactory() {
            tile.setTitle("Lazy");
            tile.setBeanFactory(beanFactory);
            when(beanFactory.getBean(MenuVisibilityProvider.class)).thenReturn(menuVisibilityProvider);
            when(menuVisibilityProvider.isMenuVisible("Lazy")).thenReturn(true);

            assertTrue(tile.isOn());
            verify(beanFactory).getBean(MenuVisibilityProvider.class);
        }

        @Test
        void shouldReturnTrueWhenBeanFactoryThrows() {
            tile.setTitle("NoProvider");
            tile.setBeanFactory(beanFactory);
            when(beanFactory.getBean(MenuVisibilityProvider.class))
                .thenThrow(new RuntimeException("kein Bean"));

            assertTrue(tile.isOn());
        }
    }
}

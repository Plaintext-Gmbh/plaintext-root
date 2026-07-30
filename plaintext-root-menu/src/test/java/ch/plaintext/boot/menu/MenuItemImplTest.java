/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.MenuVisibilityProvider;
import ch.plaintext.modules.ModuleEnablementProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.BeanFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MenuItemImplTest {

    private MenuItemImpl menuItem;

    @Mock
    private SecurityProvider securityProvider;

    @Mock
    private MenuVisibilityProvider menuVisibilityProvider;

    @Mock
    private ModuleEnablementProvider moduleEnablementProvider;

    @Mock
    private BeanFactory beanFactory;

    @BeforeEach
    void setUp() {
        menuItem = new MenuItemImpl();
    }

    @Nested
    class DefaultValues {

        @Test
        void shouldHaveDefaultOrder() {
            assertEquals(0, menuItem.getOrder());
        }

        @Test
        void shouldHaveEmptyTitle() {
            assertEquals("", menuItem.getTitle());
        }

        @Test
        void shouldHaveEmptyParent() {
            assertEquals("", menuItem.getParent());
        }

        @Test
        void shouldHaveEmptyCommand() {
            assertEquals("", menuItem.getCommand());
        }

        @Test
        void shouldHaveEmptyIcon() {
            assertEquals("", menuItem.getIcon());
        }

        @Test
        void shouldHaveEmptyRoles() {
            assertNotNull(menuItem.getRoles());
            assertTrue(menuItem.getRoles().isEmpty());
        }

        @Test
        void shouldHaveNullBadge() {
            assertNull(menuItem.getBadge());
        }

        @Test
        void shouldHaveNullSecurityProvider() {
            assertNull(menuItem.getSecurityProvider());
        }

        @Test
        void shouldHaveNullMenuVisibilityProvider() {
            assertNull(menuItem.getMenuVisibilityProvider());
        }

        @Test
        void shouldHaveNullBeanFactory() {
            assertNull(menuItem.getBeanFactory());
        }

        @Test
        void shouldHaveEmptyModuleId() {
            assertEquals("", menuItem.getModuleId());
        }

        @Test
        void shouldHaveNullModuleEnablementProvider() {
            assertNull(menuItem.getModuleEnablementProvider());
        }
    }

    @Nested
    class Properties {

        @Test
        void shouldSetAndGetOrder() {
            menuItem.setOrder(42);
            assertEquals(42, menuItem.getOrder());
        }

        @Test
        void shouldSetAndGetTitle() {
            menuItem.setTitle("Admin");
            assertEquals("Admin", menuItem.getTitle());
        }

        @Test
        void shouldSetAndGetParent() {
            menuItem.setParent("Root");
            assertEquals("Root", menuItem.getParent());
        }

        @Test
        void shouldSetAndGetCommand() {
            menuItem.setCommand("/admin/dashboard.xhtml");
            assertEquals("/admin/dashboard.xhtml", menuItem.getCommand());
        }

        @Test
        void shouldSetAndGetIcon() {
            menuItem.setIcon("pi pi-home");
            assertEquals("pi pi-home", menuItem.getIcon());
        }

        @Test
        void shouldSetAndGetRoles() {
            List<String> roles = Arrays.asList("ADMIN", "USER");
            menuItem.setRoles(roles);
            assertEquals(roles, menuItem.getRoles());
        }

        @Test
        void shouldSetAndGetBadge() {
            menuItem.setBadge("5");
            assertEquals("5", menuItem.getBadge());
        }

        @Test
        void shouldSetAndGetSecurityProvider() {
            menuItem.setSecurityProvider(securityProvider);
            assertSame(securityProvider, menuItem.getSecurityProvider());
        }

        @Test
        void shouldSetAndGetMenuVisibilityProvider() {
            menuItem.setMenuVisibilityProvider(menuVisibilityProvider);
            assertSame(menuVisibilityProvider, menuItem.getMenuVisibilityProvider());
        }

        @Test
        void shouldSetAndGetBeanFactory() {
            menuItem.setBeanFactory(beanFactory);
            assertSame(beanFactory, menuItem.getBeanFactory());
        }

        @Test
        void shouldSetAndGetModuleId() {
            menuItem.setModuleId("secrets");
            assertEquals("secrets", menuItem.getModuleId());
        }

        @Test
        void shouldSetAndGetModuleEnablementProvider() {
            menuItem.setModuleEnablementProvider(moduleEnablementProvider);
            assertSame(moduleEnablementProvider, menuItem.getModuleEnablementProvider());
        }
    }

    @Nested
    class GetIc {

        @Test
        void shouldReturnIconValue() {
            menuItem.setIcon("pi pi-cog");
            assertEquals("pi pi-cog", menuItem.getIc());
        }

        @Test
        void shouldReturnEmptyStringWhenIconNotSet() {
            assertEquals("", menuItem.getIc());
        }
    }

    @Nested
    class GetLink {

        @Test
        void shouldReturnCommandValue() {
            menuItem.setCommand("/pages/admin.xhtml");
            assertEquals("/pages/admin.xhtml", menuItem.getLink());
        }

        @Test
        void shouldReturnEmptyStringWhenCommandNotSet() {
            assertEquals("", menuItem.getLink());
        }
    }

    @Nested
    class IsOn {

        @Test
        void shouldReturnTrueWhenNoRolesAndNoVisibilityProvider() {
            // No roles, no securityProvider, no visibilityProvider
            assertTrue(menuItem.isOn());
        }

        @Test
        void shouldReturnTrueWhenRolesAreEmpty() {
            menuItem.setRoles(Collections.emptyList());
            menuItem.setSecurityProvider(securityProvider);
            assertTrue(menuItem.isOn());
        }

        @Test
        void shouldReturnTrueWhenRolesAreNull() {
            menuItem.setRoles(null);
            menuItem.setSecurityProvider(securityProvider);
            assertTrue(menuItem.isOn());
        }

        @Test
        void shouldReturnTrueWhenSecurityProviderIsNull() {
            menuItem.setRoles(List.of("ADMIN"));
            menuItem.setSecurityProvider(null);
            assertTrue(menuItem.isOn());
        }

        @Nested
        class RoleBasedVisibility {

            @Test
            void shouldReturnTrueWhenUserHasRequiredRole() {
                menuItem.setRoles(List.of("admin"));
                menuItem.setSecurityProvider(securityProvider);
                when(securityProvider.hasRole("ADMIN")).thenReturn(true);

                assertTrue(menuItem.isOn());
            }

            @Test
            void shouldReturnFalseWhenUserLacksAllRoles() {
                menuItem.setRoles(List.of("admin", "manager"));
                menuItem.setSecurityProvider(securityProvider);
                when(securityProvider.hasRole(any())).thenReturn(false);

                assertFalse(menuItem.isOn());
            }

            @Test
            void shouldConvertRolesToUppercase() {
                menuItem.setRoles(List.of("admin"));
                menuItem.setSecurityProvider(securityProvider);
                when(securityProvider.hasRole("ADMIN")).thenReturn(true);

                menuItem.isOn();

                verify(securityProvider).hasRole("ADMIN");
                verify(securityProvider, never()).hasRole("admin");
            }

            @Test
            void shouldReturnTrueWhenUserHasAtLeastOneRole() {
                menuItem.setRoles(Arrays.asList("admin", "user"));
                menuItem.setSecurityProvider(securityProvider);
                when(securityProvider.hasRole("ADMIN")).thenReturn(false);
                when(securityProvider.hasRole("USER")).thenReturn(true);

                assertTrue(menuItem.isOn());
            }

            @Test
            void shouldStopCheckingRolesAfterFirstMatch() {
                menuItem.setRoles(Arrays.asList("admin", "user"));
                menuItem.setSecurityProvider(securityProvider);
                when(securityProvider.hasRole("ADMIN")).thenReturn(true);

                menuItem.isOn();

                verify(securityProvider).hasRole("ADMIN");
                verify(securityProvider, never()).hasRole("USER");
            }

            @Test
            void shouldHandleNullRoleInList() {
                menuItem.setRoles(Arrays.asList(null, "admin"));
                menuItem.setSecurityProvider(securityProvider);
                when(securityProvider.hasRole(null)).thenReturn(false);
                when(securityProvider.hasRole("ADMIN")).thenReturn(true);

                assertTrue(menuItem.isOn());
            }
        }

        @Nested
        class ModuleEnablementIntegration {

            @Test
            void shouldReturnTrueWhenModuleIdEmpty() {
                menuItem.setModuleId("");
                menuItem.setBeanFactory(beanFactory);

                assertTrue(menuItem.isOn());
                verify(beanFactory, never()).getBean(ModuleEnablementProvider.class);
            }

            @Test
            void shouldReturnTrueWhenModuleEnabled() {
                menuItem.setModuleId("secrets");
                menuItem.setModuleEnablementProvider(moduleEnablementProvider);
                when(moduleEnablementProvider.isEnabled("secrets")).thenReturn(true);

                assertTrue(menuItem.isOn());
            }

            @Test
            void shouldReturnFalseWhenModuleDisabled() {
                menuItem.setModuleId("secrets");
                menuItem.setModuleEnablementProvider(moduleEnablementProvider);
                when(moduleEnablementProvider.isEnabled("secrets")).thenReturn(false);

                assertFalse(menuItem.isOn());
            }

            @Test
            void shouldLazyLoadModuleEnablementProviderFromBeanFactory() {
                menuItem.setModuleId("secrets");
                menuItem.setBeanFactory(beanFactory);
                when(beanFactory.getBean(ModuleEnablementProvider.class)).thenReturn(moduleEnablementProvider);
                when(moduleEnablementProvider.isEnabled("secrets")).thenReturn(true);

                assertTrue(menuItem.isOn());
                verify(beanFactory).getBean(ModuleEnablementProvider.class);
            }

            @Test
            void shouldReturnTrueWhenBeanFactoryThrowsExceptionForModuleProvider() {
                menuItem.setModuleId("secrets");
                menuItem.setBeanFactory(beanFactory);
                when(beanFactory.getBean(ModuleEnablementProvider.class))
                        .thenThrow(new RuntimeException("No bean found"));

                assertTrue(menuItem.isOn());
            }

            @Test
            void shouldNotLazyLoadIfModuleEnablementProviderAlreadySet() {
                menuItem.setModuleId("secrets");
                menuItem.setBeanFactory(beanFactory);
                menuItem.setModuleEnablementProvider(moduleEnablementProvider);
                when(moduleEnablementProvider.isEnabled("secrets")).thenReturn(true);

                assertTrue(menuItem.isOn());
                verify(beanFactory, never()).getBean(ModuleEnablementProvider.class);
            }

            @Test
            void shouldCheckRolesBeforeModuleEnablement() {
                menuItem.setRoles(List.of("admin"));
                menuItem.setSecurityProvider(securityProvider);
                menuItem.setModuleId("secrets");
                menuItem.setModuleEnablementProvider(moduleEnablementProvider);
                when(securityProvider.hasRole("ADMIN")).thenReturn(false);

                assertFalse(menuItem.isOn());
                verifyNoInteractions(moduleEnablementProvider);
            }

            @Test
            void shouldCheckModuleEnablementBeforeVisibilityProvider() {
                menuItem.setModuleId("secrets");
                menuItem.setModuleEnablementProvider(moduleEnablementProvider);
                menuItem.setMenuVisibilityProvider(menuVisibilityProvider);
                when(moduleEnablementProvider.isEnabled("secrets")).thenReturn(false);

                assertFalse(menuItem.isOn());
                verifyNoInteractions(menuVisibilityProvider);
            }
        }

        @Nested
        class MenuVisibilityProviderIntegration {

            @Test
            void shouldDelegateToMenuVisibilityProvider() {
                menuItem.setTitle("Zeiterfassung");
                menuItem.setParent("Root");
                menuItem.setMenuVisibilityProvider(menuVisibilityProvider);
                when(menuVisibilityProvider.isMenuVisible("Root | Zeiterfassung")).thenReturn(true);

                assertTrue(menuItem.isOn());
                verify(menuVisibilityProvider).isMenuVisible("Root | Zeiterfassung");
            }

            @Test
            void shouldReturnFalseWhenVisibilityProviderHidesMenu() {
                menuItem.setTitle("Hidden Menu");
                menuItem.setParent("Root");
                menuItem.setMenuVisibilityProvider(menuVisibilityProvider);
                when(menuVisibilityProvider.isMenuVisible("Root | Hidden Menu")).thenReturn(false);

                assertFalse(menuItem.isOn());
            }

            @Test
            void shouldLazyLoadVisibilityProviderFromBeanFactory() {
                menuItem.setTitle("Lazy");
                menuItem.setBeanFactory(beanFactory);
                when(beanFactory.getBean(MenuVisibilityProvider.class)).thenReturn(menuVisibilityProvider);
                when(menuVisibilityProvider.isMenuVisible("Lazy")).thenReturn(true);

                assertTrue(menuItem.isOn());
                verify(beanFactory).getBean(MenuVisibilityProvider.class);
            }

            @Test
            void shouldReturnTrueWhenBeanFactoryThrowsException() {
                menuItem.setTitle("NoProvider");
                menuItem.setBeanFactory(beanFactory);
                when(beanFactory.getBean(MenuVisibilityProvider.class))
                        .thenThrow(new RuntimeException("No bean found"));

                assertTrue(menuItem.isOn());
            }

            @Test
            void shouldReturnTrueWhenBeanFactoryIsNullAndNoProvider() {
                menuItem.setTitle("NoBeanFactory");
                menuItem.setBeanFactory(null);
                menuItem.setMenuVisibilityProvider(null);

                assertTrue(menuItem.isOn());
            }

            @Test
            void shouldNotLazyLoadIfVisibilityProviderAlreadySet() {
                menuItem.setTitle("AlreadySet");
                menuItem.setBeanFactory(beanFactory);
                menuItem.setMenuVisibilityProvider(menuVisibilityProvider);
                when(menuVisibilityProvider.isMenuVisible("AlreadySet")).thenReturn(true);

                assertTrue(menuItem.isOn());
                verify(beanFactory, never()).getBean(MenuVisibilityProvider.class);
            }

            @Test
            void shouldCheckRolesBeforeVisibilityProvider() {
                menuItem.setRoles(List.of("admin"));
                menuItem.setSecurityProvider(securityProvider);
                menuItem.setMenuVisibilityProvider(menuVisibilityProvider);
                when(securityProvider.hasRole("ADMIN")).thenReturn(false);

                assertFalse(menuItem.isOn());
                verifyNoInteractions(menuVisibilityProvider);
            }

            @Test
            void shouldCheckVisibilityProviderAfterRolesPass() {
                menuItem.setTitle("Both");
                menuItem.setRoles(List.of("admin"));
                menuItem.setSecurityProvider(securityProvider);
                menuItem.setMenuVisibilityProvider(menuVisibilityProvider);
                when(securityProvider.hasRole("ADMIN")).thenReturn(true);
                when(menuVisibilityProvider.isMenuVisible("Both")).thenReturn(false);

                assertFalse(menuItem.isOn());
                verify(menuVisibilityProvider).isMenuVisible("Both");
            }
        }
    }

    @Nested
    class BuildFullTitle {

        @Test
        void shouldReturnTitleOnlyWhenParentIsEmpty() {
            menuItem.setTitle("Dashboard");
            menuItem.setParent("");

            assertEquals("Dashboard", menuItem.buildFullTitle());
        }

        @Test
        void shouldReturnTitleOnlyWhenParentIsNull() {
            menuItem.setTitle("Dashboard");
            menuItem.setParent(null);

            assertEquals("Dashboard", menuItem.buildFullTitle());
        }

        @Test
        void shouldReturnTitleOnlyWhenParentIsBlank() {
            menuItem.setTitle("Dashboard");
            menuItem.setParent("   ");

            assertEquals("Dashboard", menuItem.buildFullTitle());
        }

        @Test
        void shouldReturnParentPipeTitleWhenParentIsSet() {
            menuItem.setTitle("Zeiterfassung Einstellungen");
            menuItem.setParent("Zeiterfassung");

            assertEquals("Zeiterfassung | Zeiterfassung Einstellungen", menuItem.buildFullTitle());
        }

        @Test
        void shouldCombineParentAndTitle() {
            menuItem.setTitle("Mandate");
            menuItem.setParent("Root");

            assertEquals("Root | Mandate", menuItem.buildFullTitle());
        }
    }

    @Nested
    class InheritedFromAbstractMenuItem {

        @Test
        void shouldReturnDefaultRight() {
            assertEquals(0, menuItem.getRight());
        }
    }
}

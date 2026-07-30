/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the MandateMenuConfig entity.
 *
 * @author plaintext.ch
 * @since 1.42.0
 */
@DisplayName("MandateMenuConfig Entity Tests")
class MandateMenuConfigTest {

    private MandateMenuConfig config;

    @BeforeEach
    void setUp() {
        config = new MandateMenuConfig();
        config.setMandateName("test-mandate");
    }

    @Nested
    @DisplayName("Default State")
    class DefaultState {

        @Test
        @DisplayName("Should have null id when not persisted")
        void shouldHaveNullIdWhenNotPersisted() {
            assertNull(config.getId());
        }

        @Test
        @DisplayName("Should have empty hiddenMenus set by default")
        void shouldHaveEmptyHiddenMenusByDefault() {
            assertNotNull(config.getHiddenMenus());
            assertTrue(config.getHiddenMenus().isEmpty());
        }

        @Test
        @DisplayName("Should have blacklist mode (false) by default")
        void shouldHaveBlacklistModeByDefault() {
            assertFalse(config.getWhitelistMode());
        }

        @Test
        @DisplayName("Should implement Serializable")
        void shouldImplementSerializable() {
            assertTrue(config instanceof java.io.Serializable);
        }
    }

    @Nested
    @DisplayName("isMenuHidden")
    class IsMenuHidden {

        @Test
        @DisplayName("Should return false for menu not in hidden set")
        void shouldReturnFalseForMenuNotInHiddenSet() {
            config.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            assertFalse(config.isMenuHidden("Menu2"));
        }

        @Test
        @DisplayName("Should return true for menu in hidden set")
        void shouldReturnTrueForMenuInHiddenSet() {
            config.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2")));
            assertTrue(config.isMenuHidden("Menu1"));
        }

        @Test
        @DisplayName("Should return false when hiddenMenus is null")
        void shouldReturnFalseWhenHiddenMenusIsNull() {
            config.setHiddenMenus(null);
            assertFalse(config.isMenuHidden("AnyMenu"));
        }

        @Test
        @DisplayName("Should return false for empty hiddenMenus set")
        void shouldReturnFalseForEmptyHiddenMenusSet() {
            config.setHiddenMenus(new HashSet<>());
            assertFalse(config.isMenuHidden("AnyMenu"));
        }

        @Test
        @DisplayName("Should be case sensitive")
        void shouldBeCaseSensitive() {
            config.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            assertTrue(config.isMenuHidden("Menu1"));
            assertFalse(config.isMenuHidden("menu1"));
            assertFalse(config.isMenuHidden("MENU1"));
        }
    }

    @Nested
    @DisplayName("hideMenu")
    class HideMenu {

        @Test
        @DisplayName("Should add menu to hidden set")
        void shouldAddMenuToHiddenSet() {
            config.hideMenu("NewMenu");
            assertTrue(config.getHiddenMenus().contains("NewMenu"));
        }

        @Test
        @DisplayName("Should create hiddenMenus set if null")
        void shouldCreateHiddenMenusSetIfNull() {
            config.setHiddenMenus(null);
            config.hideMenu("Menu1");
            assertNotNull(config.getHiddenMenus());
            assertTrue(config.getHiddenMenus().contains("Menu1"));
        }

        @Test
        @DisplayName("Should not duplicate existing entry")
        void shouldNotDuplicateExistingEntry() {
            config.hideMenu("Menu1");
            config.hideMenu("Menu1");
            assertEquals(1, config.getHiddenMenus().size());
        }

        @Test
        @DisplayName("Should add multiple different menus")
        void shouldAddMultipleDifferentMenus() {
            config.hideMenu("Menu1");
            config.hideMenu("Menu2");
            config.hideMenu("Menu3");
            assertEquals(3, config.getHiddenMenus().size());
            assertTrue(config.getHiddenMenus().containsAll(Set.of("Menu1", "Menu2", "Menu3")));
        }
    }

    @Nested
    @DisplayName("showMenu")
    class ShowMenu {

        @Test
        @DisplayName("Should remove menu from hidden set")
        void shouldRemoveMenuFromHiddenSet() {
            config.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2")));
            config.showMenu("Menu1");
            assertFalse(config.getHiddenMenus().contains("Menu1"));
            assertTrue(config.getHiddenMenus().contains("Menu2"));
        }

        @Test
        @DisplayName("Should handle showing menu that is not hidden")
        void shouldHandleShowingMenuThatIsNotHidden() {
            config.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            config.showMenu("NonExistent");
            assertEquals(1, config.getHiddenMenus().size());
        }

        @Test
        @DisplayName("Should handle null hiddenMenus gracefully")
        void shouldHandleNullHiddenMenusGracefully() {
            config.setHiddenMenus(null);
            assertDoesNotThrow(() -> config.showMenu("AnyMenu"));
        }

        @Test
        @DisplayName("Should result in empty set when removing last item")
        void shouldResultInEmptySetWhenRemovingLastItem() {
            config.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            config.showMenu("Menu1");
            assertTrue(config.getHiddenMenus().isEmpty());
        }
    }

    @Nested
    @DisplayName("hideMenu and showMenu combined")
    class HideAndShowCombined {

        @Test
        @DisplayName("Should support hide then show round-trip")
        void shouldSupportHideThenShowRoundTrip() {
            config.hideMenu("Menu1");
            assertTrue(config.isMenuHidden("Menu1"));

            config.showMenu("Menu1");
            assertFalse(config.isMenuHidden("Menu1"));
        }

        @Test
        @DisplayName("Should support multiple hide/show cycles")
        void shouldSupportMultipleHideShowCycles() {
            for (int i = 0; i < 5; i++) {
                config.hideMenu("ToggleMenu");
                assertTrue(config.isMenuHidden("ToggleMenu"));
                config.showMenu("ToggleMenu");
                assertFalse(config.isMenuHidden("ToggleMenu"));
            }
        }
    }

    @Nested
    @DisplayName("Setters and Getters")
    class SettersAndGetters {

        @Test
        @DisplayName("Should set and get mandateName")
        void shouldSetAndGetMandateName() {
            config.setMandateName("my-mandate");
            assertEquals("my-mandate", config.getMandateName());
        }

        @Test
        @DisplayName("Should set and get whitelistMode")
        void shouldSetAndGetWhitelistMode() {
            config.setWhitelistMode(true);
            assertTrue(config.getWhitelistMode());

            config.setWhitelistMode(false);
            assertFalse(config.getWhitelistMode());
        }

        @Test
        @DisplayName("Should set and get id")
        void shouldSetAndGetId() {
            config.setId(42L);
            assertEquals(42L, config.getId());
        }

        @Test
        @DisplayName("Should set and get hiddenMenus")
        void shouldSetAndGetHiddenMenus() {
            Set<String> menus = new HashSet<>(Set.of("A", "B", "C"));
            config.setHiddenMenus(menus);
            assertEquals(menus, config.getHiddenMenus());
        }

        @Test
        @DisplayName("Should allow null whitelistMode")
        void shouldAllowNullWhitelistMode() {
            config.setWhitelistMode(null);
            assertNull(config.getWhitelistMode());
        }
    }

    @Nested
    @DisplayName("Equals and HashCode (Lombok @Data)")
    class EqualsAndHashCode {

        @Test
        @DisplayName("Should be equal when all fields match")
        void shouldBeEqualWhenAllFieldsMatch() {
            MandateMenuConfig config1 = new MandateMenuConfig();
            config1.setId(1L);
            config1.setMandateName("mandate1");
            config1.setWhitelistMode(false);
            config1.setHiddenMenus(new HashSet<>(Set.of("Menu1")));

            MandateMenuConfig config2 = new MandateMenuConfig();
            config2.setId(1L);
            config2.setMandateName("mandate1");
            config2.setWhitelistMode(false);
            config2.setHiddenMenus(new HashSet<>(Set.of("Menu1")));

            assertEquals(config1, config2);
            assertEquals(config1.hashCode(), config2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when mandateName differs")
        void shouldNotBeEqualWhenMandateNameDiffers() {
            MandateMenuConfig config1 = new MandateMenuConfig();
            config1.setMandateName("mandate1");

            MandateMenuConfig config2 = new MandateMenuConfig();
            config2.setMandateName("mandate2");

            assertNotEquals(config1, config2);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void shouldNotBeEqualToNull() {
            assertNotEquals(null, config);
        }

        @Test
        @DisplayName("Should not be equal to different type")
        void shouldNotBeEqualToDifferentType() {
            assertNotEquals("not a config", config);
        }
    }
}

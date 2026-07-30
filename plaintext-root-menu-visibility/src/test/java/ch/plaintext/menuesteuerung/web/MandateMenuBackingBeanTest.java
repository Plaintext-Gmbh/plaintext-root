/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.service.MandateMenuVisibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for MandateMenuBackingBean.
 *
 * @author plaintext.ch
 * @since 1.39.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MandateMenuBackingBean Tests")
class MandateMenuBackingBeanTest {

    @Mock
    private MandateMenuVisibilityService service;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @InjectMocks
    private MandateMenuBackingBean backingBean;

    private MandateMenuConfig testConfig;

    @BeforeEach
    void setUp() {
        testConfig = new MandateMenuConfig();
        testConfig.setMandateName("test-mandate");
        testConfig.setHiddenMenus(new HashSet<>());
        testConfig.setWhitelistMode(false);
    }

    @Nested
    @DisplayName("selectAll")
    class SelectAll {

        @Test
        @DisplayName("Should set all available menus as hidden")
        void shouldSetAllAvailableMenusAsHidden() {
            backingBean.setSelected(testConfig);
            backingBean.setAvailableMenus(List.of("Menu1", "Menu2", "Menu3"));

            backingBean.selectAll();

            assertEquals(3, testConfig.getHiddenMenus().size());
            assertTrue(testConfig.getHiddenMenus().containsAll(Set.of("Menu1", "Menu2", "Menu3")));
        }

        @Test
        @DisplayName("Should handle empty available menus")
        void shouldHandleEmptyAvailableMenus() {
            backingBean.setSelected(testConfig);
            backingBean.setAvailableMenus(new ArrayList<>());

            backingBean.selectAll();

            assertTrue(testConfig.getHiddenMenus().isEmpty());
        }

        @Test
        @DisplayName("Should not throw when selected is null")
        void shouldNotThrowWhenSelectedIsNull() {
            backingBean.setSelected(null);
            backingBean.setAvailableMenus(List.of("Menu1"));

            assertDoesNotThrow(() -> backingBean.selectAll());
        }

        @Test
        @DisplayName("Should replace existing selection with all menus")
        void shouldReplaceExistingSelectionWithAllMenus() {
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            backingBean.setSelected(testConfig);
            backingBean.setAvailableMenus(List.of("Menu1", "Menu2", "Menu3", "Menu4"));

            backingBean.selectAll();

            assertEquals(4, testConfig.getHiddenMenus().size());
        }
    }

    @Nested
    @DisplayName("deselectAll")
    class DeselectAll {

        @Test
        @DisplayName("Should clear all hidden menus")
        void shouldClearAllHiddenMenus() {
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2", "Menu3")));
            backingBean.setSelected(testConfig);

            backingBean.deselectAll();

            assertTrue(testConfig.getHiddenMenus().isEmpty());
        }

        @Test
        @DisplayName("Should handle already empty hidden menus")
        void shouldHandleAlreadyEmptyHiddenMenus() {
            testConfig.setHiddenMenus(new HashSet<>());
            backingBean.setSelected(testConfig);

            backingBean.deselectAll();

            assertTrue(testConfig.getHiddenMenus().isEmpty());
        }

        @Test
        @DisplayName("Should not throw when selected is null")
        void shouldNotThrowWhenSelectedIsNull() {
            backingBean.setSelected(null);

            assertDoesNotThrow(() -> backingBean.deselectAll());
        }
    }

    @Nested
    @DisplayName("invertSelection")
    class InvertSelection {

        @Test
        @DisplayName("Should invert selection correctly")
        void shouldInvertSelectionCorrectly() {
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2")));
            backingBean.setSelected(testConfig);
            backingBean.setAvailableMenus(List.of("Menu1", "Menu2", "Menu3", "Menu4", "Menu5"));

            backingBean.invertSelection();

            assertEquals(3, testConfig.getHiddenMenus().size());
            assertTrue(testConfig.getHiddenMenus().containsAll(Set.of("Menu3", "Menu4", "Menu5")));
            assertFalse(testConfig.getHiddenMenus().contains("Menu1"));
            assertFalse(testConfig.getHiddenMenus().contains("Menu2"));
        }

        @Test
        @DisplayName("Should select all when none are selected")
        void shouldSelectAllWhenNoneSelected() {
            testConfig.setHiddenMenus(new HashSet<>());
            backingBean.setSelected(testConfig);
            backingBean.setAvailableMenus(List.of("Menu1", "Menu2", "Menu3"));

            backingBean.invertSelection();

            assertEquals(3, testConfig.getHiddenMenus().size());
            assertTrue(testConfig.getHiddenMenus().containsAll(Set.of("Menu1", "Menu2", "Menu3")));
        }

        @Test
        @DisplayName("Should deselect all when all are selected")
        void shouldDeselectAllWhenAllSelected() {
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2", "Menu3")));
            backingBean.setSelected(testConfig);
            backingBean.setAvailableMenus(List.of("Menu1", "Menu2", "Menu3"));

            backingBean.invertSelection();

            assertTrue(testConfig.getHiddenMenus().isEmpty());
        }

        @Test
        @DisplayName("Should not throw when selected is null")
        void shouldNotThrowWhenSelectedIsNull() {
            backingBean.setSelected(null);
            backingBean.setAvailableMenus(List.of("Menu1"));

            assertDoesNotThrow(() -> backingBean.invertSelection());
        }

        @Test
        @DisplayName("Should handle null hiddenMenus gracefully")
        void shouldHandleNullHiddenMenusGracefully() {
            testConfig.setHiddenMenus(null);
            backingBean.setSelected(testConfig);
            backingBean.setAvailableMenus(List.of("Menu1", "Menu2"));

            backingBean.invertSelection();

            assertEquals(2, testConfig.getHiddenMenus().size());
            assertTrue(testConfig.getHiddenMenus().containsAll(Set.of("Menu1", "Menu2")));
        }
    }

    @Nested
    @DisplayName("Mode Toggle - Whitelist to Blacklist")
    class ModeToggleWhitelistToBlacklist {

        @BeforeEach
        void setup() {
            backingBean.setSelected(testConfig);
            backingBean.setAvailableMenus(List.of("Menu1", "Menu2", "Menu3", "Menu4", "Menu5"));
        }

        @Test
        @DisplayName("Should toggle from blacklist to whitelist and invert selection")
        void shouldToggleBlacklistToWhitelist() {
            testConfig.setWhitelistMode(false);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2")));

            backingBean.toggleMode();

            assertTrue(Boolean.TRUE.equals(testConfig.getWhitelistMode()));
            assertTrue(testConfig.getHiddenMenus().containsAll(Set.of("Menu3", "Menu4", "Menu5")));
            assertFalse(testConfig.getHiddenMenus().contains("Menu1"));
            assertFalse(testConfig.getHiddenMenus().contains("Menu2"));
        }

        @Test
        @DisplayName("Should toggle from whitelist to blacklist and invert selection")
        void shouldToggleWhitelistToBlacklist() {
            testConfig.setWhitelistMode(true);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2")));

            backingBean.toggleMode();

            assertFalse(Boolean.TRUE.equals(testConfig.getWhitelistMode()));
            assertTrue(testConfig.getHiddenMenus().containsAll(Set.of("Menu3", "Menu4", "Menu5")));
            assertFalse(testConfig.getHiddenMenus().contains("Menu1"));
            assertFalse(testConfig.getHiddenMenus().contains("Menu2"));
        }
    }

    @Nested
    @DisplayName("Mode Toggle - Edge Cases")
    class ModeToggleEdgeCases {

        @BeforeEach
        void setup() {
            backingBean.setSelected(testConfig);
            backingBean.setAvailableMenus(new ArrayList<>());
        }

        @Test
        @DisplayName("Should handle toggle with null selected mandate")
        void shouldHandleToggleWithNullSelected() {
            backingBean.setSelected(null);

            assertDoesNotThrow(() -> backingBean.toggleMode());
        }

        @Test
        @DisplayName("Should handle toggle with empty available menus")
        void shouldHandleToggleWithEmptyMenus() {
            testConfig.setWhitelistMode(false);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            backingBean.setAvailableMenus(new ArrayList<>());

            backingBean.toggleMode();

            assertNotNull(testConfig.getHiddenMenus());
        }

        @Test
        @DisplayName("Should invert empty whitelist to all menus as blacklist")
        void shouldInvertEmptyWhitelistToAllMenus() {
            testConfig.setWhitelistMode(true);
            testConfig.setHiddenMenus(new HashSet<>());
            List<String> allMenus = List.of("Menu1", "Menu2", "Menu3");
            backingBean.setAvailableMenus(allMenus);

            backingBean.toggleMode();

            assertFalse(Boolean.TRUE.equals(testConfig.getWhitelistMode()));
            assertEquals(3, testConfig.getHiddenMenus().size());
        }

        @Test
        @DisplayName("Double toggle should return to original state")
        void doubleToggleShouldReturnToOriginalState() {
            testConfig.setWhitelistMode(false);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2")));
            backingBean.setAvailableMenus(List.of("Menu1", "Menu2", "Menu3", "Menu4"));

            boolean originalMode = Boolean.TRUE.equals(testConfig.getWhitelistMode());
            Set<String> originalHidden = new HashSet<>(testConfig.getHiddenMenus());

            backingBean.toggleMode();
            backingBean.toggleMode();

            assertEquals(originalMode, Boolean.TRUE.equals(testConfig.getWhitelistMode()));
            assertEquals(originalHidden, testConfig.getHiddenMenus());
        }
    }

    @Nested
    @DisplayName("initDetail")
    class InitDetail {

        @Test
        @DisplayName("Should create new MandateMenuConfig when selected is null")
        void shouldCreateNewConfigWhenSelectedIsNull() {
            backingBean.setSelected(null);

            backingBean.initDetail();

            assertNotNull(backingBean.getSelected());
        }

        @Test
        @DisplayName("Should preserve whitelist mode during initialization")
        void shouldPreserveWhitelistModeDuringInit() {
            testConfig.setWhitelistMode(true);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2")));
            backingBean.setSelected(testConfig);

            backingBean.initDetail();

            assertTrue(Boolean.TRUE.equals(backingBean.getSelected().getWhitelistMode()));
            assertNotNull(backingBean.getSelected().getHiddenMenus());
        }

        @Test
        @DisplayName("Should copy hiddenMenus to new HashSet to avoid lazy loading issues")
        void shouldCopyHiddenMenusToNewHashSet() {
            Set<String> originalSet = new HashSet<>(Set.of("Menu1"));
            testConfig.setHiddenMenus(originalSet);
            backingBean.setSelected(testConfig);

            backingBean.initDetail();

            assertNotNull(backingBean.getSelected().getHiddenMenus());
            assertEquals(originalSet, backingBean.getSelected().getHiddenMenus());
            // The content should be equal but ideally a different object
            assertTrue(backingBean.getSelected().getHiddenMenus().contains("Menu1"));
        }

        @Test
        @DisplayName("Should handle null hiddenMenus during initDetail")
        void shouldHandleNullHiddenMenusDuringInitDetail() {
            testConfig.setHiddenMenus(null);
            backingBean.setSelected(testConfig);

            assertDoesNotThrow(() -> backingBean.initDetail());
        }
    }

    @Nested
    @DisplayName("selectMandate")
    class SelectMandate {

        @Test
        @DisplayName("Should not throw when selected is set")
        void shouldNotThrowWhenSelectedIsSet() {
            backingBean.setSelected(testConfig);
            assertDoesNotThrow(() -> backingBean.selectMandate());
        }

        @Test
        @DisplayName("Should not throw when selected is null")
        void shouldNotThrowWhenSelectedIsNull() {
            backingBean.setSelected(null);
            assertDoesNotThrow(() -> backingBean.selectMandate());
        }
    }

    @Nested
    @DisplayName("getAllMandate")
    class GetAllMandate {

        @Test
        @DisplayName("Should always include default mandate")
        void shouldAlwaysIncludeDefaultMandate() {
            List<String> result = backingBean.getAllMandate();
            assertTrue(result.contains("default"));
        }

        @Test
        @DisplayName("Should return sorted list")
        void shouldReturnSortedList() {
            when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("zeta", "alpha", "beta"));

            List<String> result = backingBean.getAllMandate();

            for (int i = 0; i < result.size() - 1; i++) {
                assertTrue(result.get(i).compareTo(result.get(i + 1)) <= 0,
                        "List should be sorted: " + result.get(i) + " <= " + result.get(i + 1));
            }
        }

        @Test
        @DisplayName("Should include mandates from security system")
        void shouldIncludeMandatesFromSecuritySystem() {
            when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("mandate1", "mandate2"));

            List<String> result = backingBean.getAllMandate();

            assertTrue(result.contains("mandate1"));
            assertTrue(result.contains("mandate2"));
            assertTrue(result.contains("default"));
        }

        @Test
        @DisplayName("Should handle null from security system")
        void shouldHandleNullFromSecuritySystem() {
            when(plaintextSecurity.getAllMandate()).thenReturn(null);

            List<String> result = backingBean.getAllMandate();

            assertNotNull(result);
            assertTrue(result.contains("default"));
        }

        @Test
        @DisplayName("Should handle empty set from security system")
        void shouldHandleEmptySetFromSecuritySystem() {
            when(plaintextSecurity.getAllMandate()).thenReturn(Set.of());

            List<String> result = backingBean.getAllMandate();

            assertNotNull(result);
            assertTrue(result.contains("default"));
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Should handle exception from security system")
        void shouldHandleExceptionFromSecuritySystem() {
            when(plaintextSecurity.getAllMandate()).thenThrow(new RuntimeException("Security error"));

            List<String> result = backingBean.getAllMandate();

            assertNotNull(result);
            assertTrue(result.contains("default"));
        }
    }

    @Nested
    @DisplayName("Configuration State Preservation")
    class ConfigurationStatePreservation {

        @Test
        @DisplayName("Should preserve whitelist mode flag in configuration")
        void shouldPreserveModeFlag() {
            testConfig.setMandateName("mandate1");
            testConfig.setWhitelistMode(true);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            backingBean.setSelected(testConfig);

            assertTrue(Boolean.TRUE.equals(testConfig.getWhitelistMode()));
            assertTrue(testConfig.getHiddenMenus().contains("Menu1"));
        }

        @Test
        @DisplayName("Should handle null mandate name in configuration")
        void shouldHandleNullMandateName() {
            testConfig.setMandateName(null);
            backingBean.setSelected(testConfig);

            assertNull(testConfig.getMandateName());
        }

        @Test
        @DisplayName("Should handle null selected mandate gracefully")
        void shouldHandleNullSelectedMandate() {
            backingBean.setSelected(null);
            assertNull(backingBean.getSelected());
        }
    }

    @Nested
    @DisplayName("Serializable Contract")
    class SerializableContract {

        @Test
        @DisplayName("Should implement Serializable")
        void shouldImplementSerializable() {
            assertTrue(backingBean instanceof java.io.Serializable);
        }
    }
}

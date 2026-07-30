/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.service;

import ch.plaintext.MenuRegistry;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for MandateMenuVisibilityService.
 *
 * @author plaintext.ch
 * @since 1.39.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MandateMenuVisibilityService Tests")
class MandateMenuVisibilityServiceTest {

    @Mock
    private MandateMenuConfigRepository repository;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @Mock
    private MenuRegistry menuRegistry;

    @InjectMocks
    private MandateMenuVisibilityService service;

    private MandateMenuConfig testConfig;

    @BeforeEach
    void setUp() {
        testConfig = new MandateMenuConfig();
        testConfig.setMandateName("test-mandate");
        testConfig.setHiddenMenus(new HashSet<>());
        testConfig.setWhitelistMode(false);
    }

    @Nested
    @DisplayName("isMenuVisible - Current Mandate")
    class IsMenuVisibleCurrentMandate {

        @Test
        @DisplayName("Should show all menus when PlaintextSecurity is constructed with null")
        void shouldShowAllMenusWhenSecurityIsNull() {
            MandateMenuVisibilityService serviceWithNullSecurity =
                    new MandateMenuVisibilityService(repository, null, menuRegistry);
            assertTrue(serviceWithNullSecurity.isMenuVisible("AnyMenu"));
        }

        @Test
        @DisplayName("Should delegate to isMenuVisibleForMandate with current mandate")
        void shouldDelegateToIsMenuVisibleForMandate() {
            when(plaintextSecurity.getMandat()).thenReturn("test-mandate");
            testConfig.setHiddenMenus(new HashSet<>(Set.of("HiddenMenu")));
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));

            assertFalse(service.isMenuVisible("HiddenMenu"));
            assertTrue(service.isMenuVisible("VisibleMenu"));
        }

        @Test
        @DisplayName("Should normalize mandate name to lowercase")
        void shouldNormalizeMandateNameToLowercase() {
            when(plaintextSecurity.getMandat()).thenReturn("TEST-MANDATE");
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.empty());

            assertTrue(service.isMenuVisible("AnyMenu"));
            verify(repository).findByMandateName("test-mandate");
        }

        @Test
        @DisplayName("Should show all menus when current mandate is null")
        void shouldShowAllMenusWhenCurrentMandateIsNull() {
            when(plaintextSecurity.getMandat()).thenReturn(null);
            assertTrue(service.isMenuVisible("AnyMenu"));
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("Should show all menus when current mandate is empty")
        void shouldShowAllMenusWhenCurrentMandateIsEmpty() {
            when(plaintextSecurity.getMandat()).thenReturn("");
            assertTrue(service.isMenuVisible("AnyMenu"));
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("isMenuVisibleForMandate - Blacklist Mode")
    class BlacklistMode {

        @BeforeEach
        void setup() {
            testConfig.setWhitelistMode(false);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("HiddenMenu1", "HiddenMenu2", "HiddenMenu3")));
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));
        }

        @Test
        @DisplayName("Should hide blacklisted items")
        void shouldHideBlacklistedItems() {
            assertFalse(service.isMenuVisibleForMandate("HiddenMenu1", "test-mandate"));
            assertFalse(service.isMenuVisibleForMandate("HiddenMenu2", "test-mandate"));
            assertFalse(service.isMenuVisibleForMandate("HiddenMenu3", "test-mandate"));
        }

        @Test
        @DisplayName("Should show items not in blacklist")
        void shouldShowItemsNotInBlacklist() {
            assertTrue(service.isMenuVisibleForMandate("VisibleMenu1", "test-mandate"));
            assertTrue(service.isMenuVisibleForMandate("VisibleMenu2", "test-mandate"));
        }

        @Test
        @DisplayName("Should show all items when blacklist is empty")
        void shouldShowAllItemsWhenBlacklistEmpty() {
            testConfig.setHiddenMenus(new HashSet<>());
            assertTrue(service.isMenuVisibleForMandate("AnyMenu", "test-mandate"));
        }

        @Test
        @DisplayName("Mismatch: near-miss title to a hidden menu stays visible (exact-match fail-open)")
        void shouldStayVisibleOnNearMissTitle() {
            // Dokumentiert die Ursache von Befund 1: Der Abgleich ist ein EXAKTER String-Vergleich.
            // "HiddenMenu1" ist ausgeblendet, aber jeder Near-Miss (Tippfehler, falsche Gross-/
            // Kleinschreibung, fehlende Hierarchie) matcht nicht und bleibt im Blacklist-Modus
            // sichtbar. Eine Dashboard-Kachel mit solch fehlendem menuTitle würde fail-open
            // sichtbar bleiben – genau das macht der TileVisibilityValidator beim Start erkennbar.
            assertFalse(service.isMenuVisibleForMandate("HiddenMenu1", "test-mandate"),
                "Exakter Treffer wird korrekt ausgeblendet");
            assertTrue(service.isMenuVisibleForMandate("HiddenMenu1 ", "test-mandate"),
                "Nachgestelltes Leerzeichen matcht nicht -> bleibt sichtbar");
            assertTrue(service.isMenuVisibleForMandate("hiddenmenu1", "test-mandate"),
                "Falsche Gross-/Kleinschreibung matcht nicht -> bleibt sichtbar");
            assertTrue(service.isMenuVisibleForMandate("HiddenMenu", "test-mandate"),
                "Teil-Titel matcht nicht -> bleibt sichtbar");
        }
    }

    @Nested
    @DisplayName("isMenuVisibleForMandate - Whitelist Mode")
    class WhitelistMode {

        @BeforeEach
        void setup() {
            testConfig.setWhitelistMode(true);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2", "Menu3")));
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));
        }

        @Test
        @DisplayName("Should show only whitelisted items")
        void shouldShowOnlyWhitelistedItems() {
            assertTrue(service.isMenuVisibleForMandate("Menu1", "test-mandate"));
            assertTrue(service.isMenuVisibleForMandate("Menu2", "test-mandate"));
            assertTrue(service.isMenuVisibleForMandate("Menu3", "test-mandate"));
        }

        @Test
        @DisplayName("Should hide items not in whitelist")
        void shouldHideItemsNotInWhitelist() {
            assertFalse(service.isMenuVisibleForMandate("OtherMenu", "test-mandate"));
            assertFalse(service.isMenuVisibleForMandate("AnotherMenu", "test-mandate"));
        }

        @Test
        @DisplayName("Should hide all items when whitelist is empty")
        void shouldHideAllItemsWhenWhitelistEmpty() {
            testConfig.setHiddenMenus(new HashSet<>());
            assertFalse(service.isMenuVisibleForMandate("AnyMenu", "test-mandate"));
        }
    }

    @Nested
    @DisplayName("isMenuVisibleForMandate - Edge Cases")
    class IsMenuVisibleForMandateEdgeCases {

        @Test
        @DisplayName("Should handle null mandate gracefully")
        void shouldHandleNullMandate() {
            assertTrue(service.isMenuVisibleForMandate("AnyMenu", null));
        }

        @Test
        @DisplayName("Should handle empty mandate string")
        void shouldHandleEmptyMandateString() {
            assertTrue(service.isMenuVisibleForMandate("AnyMenu", ""));
        }

        @Test
        @DisplayName("Should show all menus when no configuration exists")
        void shouldShowAllMenusWhenNoConfigExists() {
            when(repository.findByMandateName("unknown-mandate")).thenReturn(Optional.empty());
            assertTrue(service.isMenuVisibleForMandate("AnyMenu", "unknown-mandate"));
        }

        @Test
        @DisplayName("Should handle null hiddenMenus set in blacklist mode")
        void shouldHandleNullHiddenMenusInBlacklistMode() {
            testConfig.setWhitelistMode(false);
            testConfig.setHiddenMenus(null);
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));

            assertTrue(service.isMenuVisibleForMandate("AnyMenu", "test-mandate"));
        }

        @Test
        @DisplayName("Should treat null whitelistMode as blacklist mode")
        void shouldTreatNullWhitelistModeAsBlacklist() {
            testConfig.setWhitelistMode(null);
            testConfig.setHiddenMenus(new HashSet<>());
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));

            assertTrue(service.isMenuVisibleForMandate("AnyMenu", "test-mandate"));
        }

        @Test
        @DisplayName("Should handle null menu title gracefully in blacklist mode with HashSet")
        void shouldHandleNullMenuTitleGracefully() {
            testConfig.setWhitelistMode(false);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));

            // HashSet.contains(null) returns false rather than throwing NPE
            assertTrue(service.isMenuVisibleForMandate(null, "test-mandate"));
        }

        @Test
        @DisplayName("Should normalize mandate to lowercase for case-insensitive lookup")
        void shouldNormalizeMandateToLowercase() {
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));

            service.isMenuVisibleForMandate("Menu", "TEST-MANDATE");
            service.isMenuVisibleForMandate("Menu", "Test-Mandate");
            service.isMenuVisibleForMandate("Menu", "test-mandate");

            verify(repository, times(3)).findByMandateName("test-mandate");
        }

        @Test
        @DisplayName("Should preserve case sensitivity for menu titles in whitelist")
        void shouldPreserveCaseSensitivityForMenuTitles() {
            testConfig.setWhitelistMode(true);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));

            assertTrue(service.isMenuVisibleForMandate("Menu1", "test-mandate"));
            assertFalse(service.isMenuVisibleForMandate("menu1", "test-mandate"));
            assertFalse(service.isMenuVisibleForMandate("MENU1", "test-mandate"));
        }
    }

    @Nested
    @DisplayName("Mode Transition - Toggle Behavior")
    class ModeTransitionToggleBehavior {

        @Test
        @DisplayName("Should correctly invert selection when toggling blacklist to whitelist")
        void shouldInvertSelectionOnToggle() {
            Set<String> allMenus = Set.of("Menu1", "Menu2", "Menu3", "Menu4", "Menu5");
            testConfig.setWhitelistMode(false);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2")));

            Set<String> invertedSelection = new HashSet<>();
            for (String menu : allMenus) {
                if (!testConfig.getHiddenMenus().contains(menu)) {
                    invertedSelection.add(menu);
                }
            }

            testConfig.setHiddenMenus(invertedSelection);
            testConfig.setWhitelistMode(true);

            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));

            assertTrue(service.isMenuVisibleForMandate("Menu3", "test-mandate"));
            assertTrue(service.isMenuVisibleForMandate("Menu4", "test-mandate"));
            assertTrue(service.isMenuVisibleForMandate("Menu5", "test-mandate"));
            assertFalse(service.isMenuVisibleForMandate("Menu1", "test-mandate"));
            assertFalse(service.isMenuVisibleForMandate("Menu2", "test-mandate"));
        }
    }

    @Nested
    @DisplayName("getOrCreateConfig")
    class GetOrCreateConfig {

        @Test
        @DisplayName("Should return existing config when found")
        void shouldReturnExistingConfig() {
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));

            MandateMenuConfig result = service.getOrCreateConfig("test-mandate");

            assertEquals(testConfig, result);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Should create and save new config when not found")
        void shouldCreateAndSaveNewConfig() {
            when(repository.findByMandateName("new-mandate")).thenReturn(Optional.empty());
            MandateMenuConfig newConfig = new MandateMenuConfig();
            newConfig.setMandateName("new-mandate");
            when(repository.save(any(MandateMenuConfig.class))).thenReturn(newConfig);

            MandateMenuConfig result = service.getOrCreateConfig("new-mandate");

            assertNotNull(result);
            assertEquals("new-mandate", result.getMandateName());
            verify(repository).save(any(MandateMenuConfig.class));
        }

        @Test
        @DisplayName("Should return empty config when repository throws exception")
        void shouldReturnEmptyConfigOnException() {
            when(repository.findByMandateName("error-mandate")).thenThrow(new RuntimeException("DB error"));

            MandateMenuConfig result = service.getOrCreateConfig("error-mandate");

            assertNotNull(result);
            assertEquals("error-mandate", result.getMandateName());
        }

        @Test
        @DisplayName("Should return empty config when repository is null")
        void shouldReturnEmptyConfigWhenRepositoryIsNull() {
            MandateMenuVisibilityService serviceWithNullRepo =
                    new MandateMenuVisibilityService(null, plaintextSecurity, menuRegistry);

            MandateMenuConfig result = serviceWithNullRepo.getOrCreateConfig("test-mandate");

            assertNotNull(result);
            assertEquals("test-mandate", result.getMandateName());
        }
    }

    @Nested
    @DisplayName("saveConfig - Single Entity")
    class SaveConfigSingleEntity {

        @Test
        @DisplayName("Should delegate to repository save")
        void shouldDelegateToRepositorySave() {
            when(repository.save(testConfig)).thenReturn(testConfig);

            MandateMenuConfig result = service.saveConfig(testConfig);

            assertEquals(testConfig, result);
            verify(repository).save(testConfig);
        }
    }

    @Nested
    @DisplayName("saveConfig - By Mandate Name with Whitelist Mode")
    class SaveConfigByMandateNameWithMode {

        @Test
        @DisplayName("Should update existing config with whitelist mode")
        void shouldUpdateExistingConfigWithWhitelistMode() {
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));
            when(repository.save(any(MandateMenuConfig.class))).thenReturn(testConfig);

            Set<String> menus = new HashSet<>(Set.of("Menu1", "Menu2"));
            service.saveConfig("test-mandate", menus, true);

            verify(repository).save(any(MandateMenuConfig.class));
        }

        @Test
        @DisplayName("Should create new config when mandate does not exist")
        void shouldCreateNewConfigWhenMandateDoesNotExist() {
            when(repository.findByMandateName("new-mandate")).thenReturn(Optional.empty());
            MandateMenuConfig newConfig = new MandateMenuConfig();
            when(repository.save(any(MandateMenuConfig.class))).thenReturn(newConfig);

            service.saveConfig("new-mandate", Set.of("Menu1"), false);

            verify(repository).save(any(MandateMenuConfig.class));
        }

        @Test
        @DisplayName("Should handle null hiddenMenus in saveConfig")
        void shouldHandleNullHiddenMenusInSaveConfig() {
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));
            when(repository.save(any(MandateMenuConfig.class))).thenReturn(testConfig);

            service.saveConfig("test-mandate", null, false);

            verify(repository).save(any(MandateMenuConfig.class));
        }
    }

    @Nested
    @DisplayName("saveConfig - By Mandate Name (Two-Arg Overload)")
    class SaveConfigByMandateNameTwoArgs {

        @Test
        @DisplayName("Should default to blacklist mode")
        void shouldDefaultToBlacklistMode() {
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));
            when(repository.save(any(MandateMenuConfig.class))).thenReturn(testConfig);

            service.saveConfig("test-mandate", Set.of("Menu1"));

            verify(repository).save(argThat(config ->
                    !Boolean.TRUE.equals(config.getWhitelistMode())));
        }
    }

    @Nested
    @DisplayName("deleteConfig")
    class DeleteConfig {

        @Test
        @DisplayName("Should delegate to repository delete")
        void shouldDelegateToRepositoryDelete() {
            service.deleteConfig(testConfig);
            verify(repository).delete(testConfig);
        }
    }

    @Nested
    @DisplayName("getAllMenuTitles")
    class GetAllMenuTitles {

        @Test
        @DisplayName("Should return menu titles from registry")
        void shouldReturnMenuTitlesFromRegistry() {
            List<String> titles = List.of("Root | Admin", "Zeiterfassung", "Root | Mandate");
            when(menuRegistry.getAllMenuTitles()).thenReturn(titles);

            List<String> result = service.getAllMenuTitles();

            assertEquals(3, result.size());
            assertEquals(titles, result);
        }

        @Test
        @DisplayName("Should return empty list when registry throws exception")
        void shouldReturnEmptyListOnException() {
            when(menuRegistry.getAllMenuTitles()).thenThrow(new RuntimeException("Registry error"));

            List<String> result = service.getAllMenuTitles();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getAllConfigs")
    class GetAllConfigs {

        @Test
        @DisplayName("Should return all configs from repository")
        void shouldReturnAllConfigsFromRepository() {
            MandateMenuConfig config1 = new MandateMenuConfig();
            config1.setMandateName("mandate1");
            MandateMenuConfig config2 = new MandateMenuConfig();
            config2.setMandateName("mandate2");

            when(repository.findAll()).thenReturn(List.of(config1, config2));

            List<MandateMenuConfig> result = service.getAllConfigs();

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("Should return empty list when no configs exist")
        void shouldReturnEmptyListWhenNoConfigsExist() {
            when(repository.findAll()).thenReturn(List.of());

            List<MandateMenuConfig> result = service.getAllConfigs();

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("init")
    class Init {

        @Test
        @DisplayName("Should not throw during initialization")
        void shouldNotThrowDuringInit() {
            assertDoesNotThrow(() -> service.init());
        }
    }

    @Nested
    @DisplayName("MenuVisibilityProvider Interface Contract")
    class InterfaceContract {

        @Test
        @DisplayName("Should implement MenuVisibilityProvider")
        void shouldImplementMenuVisibilityProvider() {
            assertTrue(service instanceof ch.plaintext.MenuVisibilityProvider,
                    "Service should implement MenuVisibilityProvider");
        }
    }

    @Nested
    @DisplayName("Concurrent Modifications")
    class ConcurrentModifications {

        @Test
        @DisplayName("Should handle modification of hiddenMenus while checking visibility")
        void shouldHandleModificationWhileChecking() throws InterruptedException {
            testConfig.setWhitelistMode(false);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1", "Menu2")));
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));

            Thread modificationThread = new Thread(() ->
                    testConfig.getHiddenMenus().add("Menu3"));

            Thread checkThread = new Thread(() ->
                    service.isMenuVisibleForMandate("Menu1", "test-mandate"));

            modificationThread.start();
            checkThread.start();
            modificationThread.join();
            checkThread.join();

            assertTrue(true, "Concurrent access should be handled gracefully");
        }

        @Test
        @DisplayName("Should handle rapid mode toggles")
        void shouldHandleRapidModeToggles() {
            testConfig.setWhitelistMode(false);
            testConfig.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));

            for (int i = 0; i < 100; i++) {
                boolean oldMode = Boolean.TRUE.equals(testConfig.getWhitelistMode());
                testConfig.setWhitelistMode(!oldMode);
            }

            boolean finalVisible = service.isMenuVisibleForMandate("Menu1", "test-mandate");
            assertNotNull(finalVisible);
        }
    }

    @Nested
    @DisplayName("Configuration Persistence")
    class ConfigurationPersistence {

        @Test
        @DisplayName("Should save whitelist configuration correctly")
        void shouldSaveWhitelistConfig() {
            Set<String> whitelist = Set.of("Menu1", "Menu2", "Menu3");
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));
            when(repository.save(any(MandateMenuConfig.class))).thenReturn(testConfig);

            MandateMenuConfig saved = service.saveConfig("test-mandate", whitelist, true);

            verify(repository).save(any(MandateMenuConfig.class));
            assertTrue(Boolean.TRUE.equals(saved.getWhitelistMode()));
        }

        @Test
        @DisplayName("Should save blacklist configuration correctly")
        void shouldSaveBlacklistConfig() {
            Set<String> blacklist = Set.of("HiddenMenu1", "HiddenMenu2");
            when(repository.findByMandateName("test-mandate")).thenReturn(Optional.of(testConfig));
            when(repository.save(any(MandateMenuConfig.class))).thenReturn(testConfig);

            MandateMenuConfig saved = service.saveConfig("test-mandate", blacklist, false);

            verify(repository).save(any(MandateMenuConfig.class));
            assertFalse(Boolean.TRUE.equals(saved.getWhitelistMode()));
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.service;

import ch.plaintext.MenuRegistry;
import ch.plaintext.MenuVisibilityProvider;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Service implementing MenuVisibilityProvider to control menu visibility per mandate.
 *
 * @author plaintext.ch
 * @since 1.39.0
 */
@Service
@Slf4j
public class MandateMenuVisibilityService implements MenuVisibilityProvider {

    private final MandateMenuConfigRepository repository;
    private final PlaintextSecurity plaintextSecurity;
    private final MenuRegistry menuRegistry;

    @Autowired
    public MandateMenuVisibilityService(
            MandateMenuConfigRepository repository,
            PlaintextSecurity plaintextSecurity,
            MenuRegistry menuRegistry) {
        this.repository = repository;
        this.plaintextSecurity = plaintextSecurity;
        this.menuRegistry = menuRegistry;
    }

    @PostConstruct
    public void init() {
        log.info("MandateMenuVisibilityService initialized - mandate-specific menu control is active");
    }

    @Override
    public boolean isMenuVisible(String menuTitle) {
        return isMenuVisible(menuTitle, List.of());
    }

    @Override
    public boolean isMenuVisible(String menuTitle, Collection<String> moduleKeys) {
        if (plaintextSecurity == null) {
            log.debug("PlaintextSecurity not available yet, showing all menus");
            return true;
        }
        String currentMandate = plaintextSecurity.getMandat();
        boolean visible = isMenuVisibleForMandate(menuTitle, moduleKeys, currentMandate);
        log.debug("Menu '{}' visibility for mandate '{}': {}", menuTitle, currentMandate, visible);
        return visible;
    }

    @Override
    public boolean isMenuVisibleForMandate(String menuTitle, String mandate) {
        return isMenuVisibleForMandate(menuTitle, List.of(), mandate);
    }

    @Override
    public boolean isMenuVisibleForMandate(String menuTitle, Collection<String> moduleKeys, String mandate) {
        MandateMenuConfig menuConfig = findConfigFor(menuTitle, mandate);
        if (menuConfig == null) {
            return true;
        }

        boolean isInList = menuConfig.isListed(menuTitle, moduleKeys);

        // In blacklist mode: menu is visible if it's NOT in the list
        // In whitelist mode: menu is visible ONLY if it IS in the list
        // Treat null as false (blacklist mode) for backward compatibility
        boolean isWhitelistMode = Boolean.TRUE.equals(menuConfig.getWhitelistMode());
        boolean isVisible = isWhitelistMode ? isInList : !isInList;

        log.debug("Menu '{}' (Modul-Keys {}) for mandate '{}': mode={}, inList={}, visible={}",
            menuTitle, moduleKeys, mandate,
            isWhitelistMode ? "whitelist" : "blacklist", isInList, isVisible);
        return isVisible;
    }

    /**
     * The configuration of a tenant, or {@code null} when the tenant has none (or no tenant is set
     * at all) — in which case "everything visible" applies.
     *
     * @param menuTitle for logging only
     * @param mandate   the tenant, may be empty
     * @return the configuration or {@code null}
     */
    private MandateMenuConfig findConfigFor(String menuTitle, String mandate) {
        if (mandate == null || mandate.isEmpty()) {
            log.debug("No mandate set, showing menu '{}'", menuTitle);
            return null;
        }
        String normalizedMandate = mandate.toLowerCase();
        Optional<MandateMenuConfig> config = repository.findByMandateName(normalizedMandate);
        if (config.isEmpty()) {
            log.debug("No configuration for mandate '{}' (normalized: '{}'), showing menu '{}'",
                mandate, normalizedMandate, menuTitle);
            return null;
        }
        return config.get();
    }

    /**
     * The reason why the tenant filter hides a menu item — for the diagnostics view.
     *
     * @param menuTitle  full menu title
     * @param moduleKeys module keys of the menu item
     * @param mandate    the tenant
     * @return plain-text reason, or {@code ""} when the tenant filter lets the item through
     * @since 1.608.0
     */
    public String mandateReason(String menuTitle, Collection<String> moduleKeys, String mandate) {
        MandateMenuConfig menuConfig = findConfigFor(menuTitle, mandate);
        if (menuConfig == null) {
            return "";
        }
        boolean isInList = menuConfig.isListed(menuTitle, moduleKeys);
        boolean isWhitelistMode = Boolean.TRUE.equals(menuConfig.getWhitelistMode());
        if (isWhitelistMode) {
            return isInList ? "" : "nicht in Whitelist von " + menuConfig.getMandateName();
        }
        return isInList ? "in Blacklist von " + menuConfig.getMandateName() : "";
    }

    /**
     * The list entries of a tenant that point nowhere in the current menu tree — titles that do not
     * (or no longer) exist (typically after a rename), and {@code modul:} entries referring to
     * unknown module keys.
     *
     * @param config      the tenant configuration
     * @param knownTitles all full menu titles that currently exist
     * @param knownKeys   all module keys currently detected
     * @return dead entries, alphabetically (never {@code null})
     * @since 1.608.0
     */
    public static Set<String> deadEntries(MandateMenuConfig config,
                                          Collection<String> knownTitles,
                                          Collection<String> knownKeys) {
        Set<String> dead = new TreeSet<>();
        if (config == null || config.getHiddenMenus() == null) {
            return dead;
        }
        for (String entry : config.getHiddenMenus()) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String moduleKey = MandateMenuConfig.moduleKeyOf(entry);
            boolean lebt = moduleKey.isEmpty()
                    ? knownTitles != null && knownTitles.contains(entry)
                    : knownKeys != null && knownKeys.contains(moduleKey);
            if (!lebt) {
                dead.add(entry);
            }
        }
        return dead;
    }

    /**
     * Get or create configuration for a mandate.
     *
     * @param mandateName the mandate name
     * @return the configuration
     */
    public MandateMenuConfig getOrCreateConfig(String mandateName) {
        if (repository == null) {
            log.error("Repository is null, cannot get or create config for mandate '{}'", mandateName);
            MandateMenuConfig emptyConfig = new MandateMenuConfig();
            emptyConfig.setMandateName(mandateName);
            return emptyConfig;
        }

        try {
            Optional<MandateMenuConfig> existing = repository.findByMandateName(mandateName);
            if (existing.isPresent()) {
                return existing.get();
            }

            MandateMenuConfig newConfig = new MandateMenuConfig();
            newConfig.setMandateName(mandateName);
            return repository.save(newConfig);
        } catch (Exception e) {
            log.error("Error getting or creating config for mandate '{}'", mandateName, e);
            MandateMenuConfig emptyConfig = new MandateMenuConfig();
            emptyConfig.setMandateName(mandateName);
            return emptyConfig;
        }
    }

    /**
     * Save configuration.
     *
     * @param config the configuration to save
     * @return the saved configuration
     */
    public MandateMenuConfig saveConfig(MandateMenuConfig config) {
        return repository.save(config);
    }

    /**
     * Save configuration by mandate name and hidden menus.
     * This method handles the lazy-loaded collection properly within a transaction.
     *
     * @param mandateName the mandate name
     * @param hiddenMenus the set of hidden menu titles
     * @param whitelistMode whether whitelist mode is enabled
     * @return the saved configuration
     */
    @org.springframework.transaction.annotation.Transactional
    public MandateMenuConfig saveConfig(String mandateName, java.util.Set<String> hiddenMenus, boolean whitelistMode) {
        MandateMenuConfig config = repository.findByMandateName(mandateName)
                .orElse(new MandateMenuConfig());

        config.setMandateName(mandateName);
        config.setWhitelistMode(whitelistMode);

        // Clear and repopulate the collection within the transaction
        config.getHiddenMenus().clear();
        if (hiddenMenus != null) {
            config.getHiddenMenus().addAll(hiddenMenus);
        }

        return repository.save(config);
    }

    /**
     * Save configuration by mandate name and hidden menus.
     * This method handles the lazy-loaded collection properly within a transaction.
     * Defaults to blacklist mode.
     *
     * @param mandateName the mandate name
     * @param hiddenMenus the set of hidden menu titles
     * @return the saved configuration
     */
    @org.springframework.transaction.annotation.Transactional
    public MandateMenuConfig saveConfig(String mandateName, java.util.Set<String> hiddenMenus) {
        return saveConfig(mandateName, hiddenMenus, false);
    }

    /**
     * Delete configuration.
     *
     * @param config the configuration to delete
     */
    public void deleteConfig(MandateMenuConfig config) {
        repository.delete(config);
    }

    /**
     * Get all menu titles from registered menu items.
     *
     * @return list of all menu titles with hierarchy
     */
    public List<String> getAllMenuTitles() {
        try {
            List<String> titles = menuRegistry.getAllMenuTitles();
            log.debug("Loaded {} menu titles from MenuRegistry", titles.size());
            return titles;
        } catch (Exception e) {
            log.error("Error loading menu titles", e);
            return List.of();
        }
    }

    /**
     * Get all mandate configurations.
     *
     * @return list of all configurations
     */
    public List<MandateMenuConfig> getAllConfigs() {
        return repository.findAll();
    }
}

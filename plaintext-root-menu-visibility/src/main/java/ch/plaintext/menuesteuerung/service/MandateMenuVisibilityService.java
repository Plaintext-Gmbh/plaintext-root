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

import java.util.List;
import java.util.Optional;

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
        if (plaintextSecurity == null) {
            log.debug("PlaintextSecurity not available yet, showing all menus");
            return true;
        }
        String currentMandate = plaintextSecurity.getMandat();
        boolean visible = isMenuVisibleForMandate(menuTitle, currentMandate);
        log.debug("Menu '{}' visibility for mandate '{}': {}", menuTitle, currentMandate, visible);
        return visible;
    }

    @Override
    public boolean isMenuVisibleForMandate(String menuTitle, String mandate) {
        if (mandate == null || mandate.isEmpty()) {
            log.debug("No mandate set, showing menu '{}'", menuTitle);
            return true; // Show all menus if no mandate is set
        }

        // Normalize mandate name to lowercase for case-insensitive matching
        String normalizedMandate = mandate.toLowerCase();

        Optional<MandateMenuConfig> config = repository.findByMandateName(normalizedMandate);
        if (config.isEmpty()) {
            log.debug("No configuration for mandate '{}' (normalized: '{}'), showing menu '{}'",
                mandate, normalizedMandate, menuTitle);
            return true; // Show all menus if no configuration exists for this mandate
        }

        MandateMenuConfig menuConfig = config.get();
        boolean isInList = menuConfig.isMenuHidden(menuTitle);

        // In blacklist mode: menu is visible if it's NOT in the list
        // In whitelist mode: menu is visible ONLY if it IS in the list
        // Treat null as false (blacklist mode) for backward compatibility
        boolean isWhitelistMode = Boolean.TRUE.equals(menuConfig.getWhitelistMode());
        boolean isVisible = isWhitelistMode ? isInList : !isInList;

        log.debug("Menu '{}' for mandate '{}' (normalized: '{}'): mode={}, inList={}, visible={}",
            menuTitle, mandate, normalizedMandate,
            Boolean.TRUE.equals(menuConfig.getWhitelistMode()) ? "whitelist" : "blacklist",
            isInList, isVisible);
        return isVisible;
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

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.ISettingsService;
import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.repository.SettingRepository;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Named("settingsService")
@Slf4j
public class SettingsServiceImpl implements ISettingsService {

    private final SettingRepository repository;
    private final PlaintextSecurity security;

    public SettingsServiceImpl(SettingRepository repository, PlaintextSecurity security) {
        this.repository = repository;
        this.security = security;
    }

    @Override
    public String getString(String key, String mandat) {
        return repository.findByKeyAndMandat(key, mandat)
                .map(Setting::getValue)
                .orElse(null);
    }

    @Override
    public String getString(String key) {
        return getString(key, getCurrentMandat());
    }

    @Override
    public Integer getInt(String key, String mandat) {
        String value = getString(key, mandat);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Cannot parse int value for key={}, mandat={}, value={}", key, mandat, value);
            return null;
        }
    }

    @Override
    public Integer getInt(String key) {
        return getInt(key, getCurrentMandat());
    }

    @Override
    public Boolean getBoolean(String key, String mandat) {
        String value = getString(key, mandat);
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(value);
    }

    @Override
    public Boolean getBoolean(String key) {
        return getBoolean(key, getCurrentMandat());
    }

    @Override
    public LocalDateTime getDate(String key, String mandat) {
        String value = getString(key, mandat);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("Cannot parse date value for key={}, mandat={}, value={}", key, mandat, value);
            return null;
        }
    }

    @Override
    public LocalDateTime getDate(String key) {
        return getDate(key, getCurrentMandat());
    }

    @Override
    public List<String> getList(String key, String mandat) {
        String value = getString(key, mandat);
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getList(String key) {
        return getList(key, getCurrentMandat());
    }

    @Override
    @Transactional
    public void setSetting(String key, String mandat, String value, String valueType, String description) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        if (mandat == null || mandat.trim().isEmpty()) {
            throw new IllegalArgumentException("Mandat cannot be null or empty");
        }

        Setting setting = repository.findByKeyAndMandat(key, mandat)
                .orElse(new Setting());

        setting.setKey(key);
        setting.setMandat(mandat);
        setting.setValue(value);
        setting.setValueType(valueType != null ? valueType : "STRING");
        setting.setDescription(description);

        repository.save(setting);
        log.info("Saved setting: key={}, mandat={}", key, mandat);
    }

    @Override
    @Transactional
    public void setSetting(String key, String value, String valueType, String description) {
        setSetting(key, getCurrentMandat(), value, valueType, description);
    }

    @Override
    @Transactional
    public void deleteSetting(String key, String mandat) {
        repository.deleteByKeyAndMandat(key, mandat);
        log.info("Deleted setting: key={}, mandat={}", key, mandat);
    }

    @Override
    public boolean exists(String key, String mandat) {
        return repository.existsByKeyAndMandat(key, mandat);
    }

    @Override
    public List<String> getAllKeys(String mandat) {
        return repository.findAllKeysByMandat(mandat);
    }

    @Override
    public List<String> getChildKeys(String parentKey, String mandat) {
        String keyPrefix = parentKey + ".%";
        return repository.findByKeyPrefixAndMandat(keyPrefix, mandat)
                .stream()
                .map(Setting::getKey)
                .collect(Collectors.toList());
    }

    public List<Setting> getAllSettings(String mandat) {
        return repository.findByMandatOrderByKeyAsc(mandat);
    }

    public List<Setting> getAllSettingsForCurrentUser() {
        return getAllSettings(getCurrentMandat());
    }

    private String getCurrentMandat() {
        String mandat = security.getMandat();
        if (mandat == null || "NO_AUTH".equals(mandat) || "NO_USER".equals(mandat) || "ERROR".equals(mandat)) {
            throw new IllegalStateException("Cannot access settings - invalid mandat: " + mandat);
        }
        return mandat;
    }
}

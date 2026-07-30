/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Generic Settings Service Interface.
 * Provides access to hierarchical settings with dot-separated keys.
 * Settings are mandat-based for multi-tenancy support.
 */
public interface ISettingsService {

    /**
     * Get a string setting value.
     *
     * @param key    the dot-separated setting key
     * @param mandat the mandate/tenant identifier
     * @return the setting value, or null if not found
     */
    String getString(String key, String mandat);

    /**
     * Get a string setting value for the current user's mandat.
     *
     * @param key the dot-separated setting key
     * @return the setting value, or null if not found
     */
    String getString(String key);

    /**
     * Get an integer setting value.
     *
     * @param key    the dot-separated setting key
     * @param mandat the mandate/tenant identifier
     * @return the setting value as Integer, or null if not found
     */
    Integer getInt(String key, String mandat);

    /**
     * Get an integer setting value for the current user's mandat.
     *
     * @param key the dot-separated setting key
     * @return the setting value as Integer, or null if not found
     */
    Integer getInt(String key);

    /**
     * Get a boolean setting value.
     *
     * @param key    the dot-separated setting key
     * @param mandat the mandate/tenant identifier
     * @return the setting value as Boolean, or null if not found
     */
    Boolean getBoolean(String key, String mandat);

    /**
     * Get a boolean setting value for the current user's mandat.
     *
     * @param key the dot-separated setting key
     * @return the setting value as Boolean, or null if not found
     */
    Boolean getBoolean(String key);

    /**
     * Get a date setting value.
     *
     * @param key    the dot-separated setting key
     * @param mandat the mandate/tenant identifier
     * @return the setting value as LocalDateTime, or null if not found
     */
    LocalDateTime getDate(String key, String mandat);

    /**
     * Get a date setting value for the current user's mandat.
     *
     * @param key the dot-separated setting key
     * @return the setting value as LocalDateTime, or null if not found
     */
    LocalDateTime getDate(String key);

    /**
     * Get a list setting value (stored as a comma-separated string).
     *
     * @param key    the dot-separated setting key
     * @param mandat the mandate/tenant identifier
     * @return the setting values as a list of strings, or empty list if not found
     */
    List<String> getList(String key, String mandat);

    /**
     * Get a list setting value for the current user's mandat.
     *
     * @param key the dot-separated setting key
     * @return the setting values as a list of strings, or empty list if not found
     */
    List<String> getList(String key);

    /**
     * Set a setting value.
     *
     * @param key         the dot-separated setting key
     * @param mandat      the mandate/tenant identifier
     * @param value       the setting value as a string
     * @param valueType   the type of the value (e.g. "STRING", "INT", "BOOLEAN", "DATE", "LIST")
     * @param description a human-readable description of the setting
     */
    void setSetting(String key, String mandat, String value, String valueType, String description);

    /**
     * Set a setting value for the current user's mandat.
     *
     * @param key         the dot-separated setting key
     * @param value       the setting value as a string
     * @param valueType   the type of the value (e.g. "STRING", "INT", "BOOLEAN", "DATE", "LIST")
     * @param description a human-readable description of the setting
     */
    void setSetting(String key, String value, String valueType, String description);

    /**
     * Delete a setting.
     *
     * @param key    the dot-separated setting key
     * @param mandat the mandate/tenant identifier
     */
    void deleteSetting(String key, String mandat);

    /**
     * Check if a setting exists.
     *
     * @param key    the dot-separated setting key
     * @param mandat the mandate/tenant identifier
     * @return true if the setting exists
     */
    boolean exists(String key, String mandat);

    /**
     * Get all setting keys for a mandat.
     *
     * @param mandat the mandate/tenant identifier
     * @return list of all setting keys
     */
    List<String> getAllKeys(String mandat);

    /**
     * Get all child setting keys under a specific parent key prefix (for hierarchical navigation).
     *
     * @param parentKey the parent key prefix
     * @param mandat    the mandate/tenant identifier
     * @return list of child setting keys
     */
    List<String> getChildKeys(String parentKey, String mandat);
}

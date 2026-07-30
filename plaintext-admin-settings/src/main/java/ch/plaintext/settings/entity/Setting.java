/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Setting Entity - stores generic settings with hierarchical keys.
 * Keys are separated by dots (e.g., "mail.smtp.host") allowing navigation through the hierarchy.
 * Settings are mandat-based for multi-tenancy support.
 */
@Entity
@Table(name = "setting", indexes = {
    @Index(name = "idx_setting_mandat", columnList = "mandat"),
    @Index(name = "idx_setting_key_mandat", columnList = "setting_key, mandat", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@IdClass(SettingId.class)
public class Setting {

    @Id
    @Column(name = "setting_key", nullable = false, length = 500)
    private String key;

    @Id
    @Column(name = "mandat", nullable = false, length = 100)
    private String mandat;

    @Column(name = "setting_value", length = 5000)
    private String value;

    @Column(name = "value_type", length = 50)
    private String valueType; // STRING, INTEGER, BOOLEAN, DATE, LIST

    @Column(name = "description", length = 1000)
    private String description;

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @CreatedBy
    @Column(name = "created_by", length = 255, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "last_modified_date")
    private LocalDateTime lastModifiedDate;

    @LastModifiedBy
    @Column(name = "last_modified_by", length = 255)
    private String lastModifiedBy;

    /**
     * Get the parent key for hierarchical navigation.
     * For example, "mail.smtp.host" returns "mail.smtp"
     */
    public String getParentKey() {
        if (key == null || !key.contains(".")) {
            return null;
        }
        int lastDot = key.lastIndexOf('.');
        return key.substring(0, lastDot);
    }

    /**
     * Get the simple name (last part of the key).
     * For example, "mail.smtp.host" returns "host"
     */
    public String getSimpleName() {
        if (key == null) {
            return null;
        }
        int lastDot = key.lastIndexOf('.');
        return lastDot >= 0 ? key.substring(lastDot + 1) : key;
    }

    /**
     * Get the level in the hierarchy (number of dots + 1).
     * For example, "mail.smtp.host" returns 3
     */
    public int getLevel() {
        if (key == null) {
            return 0;
        }
        return key.split("\\.").length;
    }
}

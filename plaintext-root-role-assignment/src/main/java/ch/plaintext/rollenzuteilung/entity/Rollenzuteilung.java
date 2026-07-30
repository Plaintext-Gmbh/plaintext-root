/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.entity;

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
 * Rollenzuteilung (Role Assignment) Entity - manages user role assignments.
 */
@Entity
@Table(name = "rollenzuteilung", indexes = {
    @Index(name = "idx_rollenzuteilung_user", columnList = "username"),
    @Index(name = "idx_rollenzuteilung_mandat", columnList = "mandat"),
    @Index(name = "idx_rollenzuteilung_role", columnList = "role_name"),
    @Index(name = "idx_rollenzuteilung_unique", columnList = "username, mandat, role_name", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Rollenzuteilung {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Column(name = "mandat", nullable = false, length = 100)
    private String mandat;

    @Column(name = "role_name", nullable = false, length = 100)
    private String roleName;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "beschreibung", length = 1000)
    private String beschreibung;

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

    public boolean isCurrentlyValid() {
        LocalDateTime now = LocalDateTime.now();
        return active &&
               (validFrom == null || validFrom.isBefore(now)) &&
               (validUntil == null || validUntil.isAfter(now));
    }
}

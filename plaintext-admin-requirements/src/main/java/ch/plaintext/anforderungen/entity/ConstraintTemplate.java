/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.entity;

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
 * ConstraintTemplate Entity - stores reusable constraint/template sets.
 * These templates can be applied to multiple anforderungen for Claude automation.
 */
@Entity
@Table(name = "constraint_template", indexes = {
    @Index(name = "idx_constraint_template_titel", columnList = "titel"),
    @Index(name = "idx_constraint_template_mandat", columnList = "mandat"),
    @Index(name = "idx_constraint_template_mandat_titel", columnList = "mandat,titel", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConstraintTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mandat", nullable = false, length = 100)
    private String mandat;

    @Column(name = "titel", nullable = false, length = 200)
    private String titel;

    @Column(name = "beschreibung", length = 1000)
    private String beschreibung;

    @Column(name = "constraints_content", nullable = false, columnDefinition = "TEXT")
    private String constraintsContent;

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
}

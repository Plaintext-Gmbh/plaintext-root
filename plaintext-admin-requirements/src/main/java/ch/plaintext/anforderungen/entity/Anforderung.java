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
 * Anforderung (Requirement) Entity - stores requirements/requests.
 * Used for tracking feature requests, bug reports, or general requirements.
 */
@Entity
@Table(name = "anforderung", indexes = {
    @Index(name = "idx_anforderung_mandat", columnList = "mandat"),
    @Index(name = "idx_anforderung_status", columnList = "status"),
    @Index(name = "idx_anforderung_priority", columnList = "priority")
})
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Anforderung {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mandat", nullable = false, length = 100)
    private String mandat;

    @Column(name = "titel", nullable = false, length = 500)
    private String titel;

    @Column(name = "beschreibung", length = 10000)
    private String beschreibung;

    @Column(name = "status", length = 50)
    private String status; // OFFEN, IN_BEARBEITUNG, ERLEDIGT, ABGELEHNT

    @Column(name = "priority", length = 50)
    private String priority; // NIEDRIG, MITTEL, HOCH, KRITISCH

    @Column(name = "kategorie", length = 100)
    private String kategorie;

    @Column(name = "ersteller", length = 255)
    private String ersteller;

    @Column(name = "wiederkehrend")
    private Boolean wiederkehrend = false;

    @Column(name = "wiederkehrend_tage")
    private Integer wiederkehrendTage;

    @Column(name = "erledigt_datum")
    private LocalDateTime erledigtDatum;

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

    // Claude Automation fields (global settings moved to AnforderungApiSettings)
    @Column(name = "last_execution_date")
    private LocalDateTime lastExecutionDate;

    @Column(name = "next_execution_date")
    private LocalDateTime nextExecutionDate;

    @Column(name = "claude_summary", columnDefinition = "CLOB")
    private String claudeSummary;

    @Column(name = "user_answer", columnDefinition = "CLOB")
    private String userAnswer;

    @Column(name = "requires_user_answer")
    private Boolean requiresUserAnswer = false;

    @Column(name = "target_modules", length = 500)
    private String targetModules;

    @Column(name = "branch_naming_pattern", length = 200)
    private String branchNamingPattern;

    @Column(name = "development_cycle_info", length = 2000)
    private String developmentCycleInfo;

    @Column(name = "constraint_template_id")
    private Long constraintTemplateId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "constraint_template_id", insertable = false, updatable = false)
    private ConstraintTemplate constraintTemplate;

    @Column(name = "howto_ids", length = 500)
    private String howtoIds; // Comma-separated list of Howto IDs
}

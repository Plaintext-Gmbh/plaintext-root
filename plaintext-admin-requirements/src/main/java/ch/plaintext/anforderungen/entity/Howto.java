/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Howto Entity - stores reusable how-to instructions for Claude automation
 */
@Entity
@Table(name = "howto", indexes = {
    @Index(name = "idx_howto_name", columnList = "name"),
    @Index(name = "idx_howto_mandat", columnList = "mandat"),
    @Index(name = "idx_howto_mandat_name", columnList = "mandat,name", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Howto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mandat", nullable = false, length = 100)
    private String mandat;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "text", length = 2000, nullable = false)
    private String text;

    @Column(name = "beispiel", columnDefinition = "TEXT")
    private String beispiel;

    @Column(name = "active")
    private Boolean active = true;

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @LastModifiedDate
    @Column(name = "last_modified_date")
    private LocalDateTime lastModifiedDate;
}

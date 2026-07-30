/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate.entity;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Admin-editierbarer Override für einen System-Mailtext (Betreff+Body), identifiziert über
 * {@code templateKey} (z. B. {@code auth.registration}). Ohne Zeile für einen Key gilt der im Code
 * hinterlegte Default-Text (Aufrufer übergibt ihn direkt bei {@link
 * ch.plaintext.mailtemplate.service.MailTemplateService#render}) — kein separater Seed-Mechanismus.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Entity
@Table(name = "mail_template",
        uniqueConstraints = @UniqueConstraint(name = "uk_mailtemplate_mandat_key", columnNames = {"mandat", "template_key"}))
@Data
@EqualsAndHashCode(callSuper = false)
public class MailTemplate extends SuperModel {

    @Column(name = "template_key", length = 500, nullable = false)
    private String templateKey;

    @Column(name = "betreff", length = 500, nullable = false)
    private String betreff;

    @Column(name = "body", length = 8000, nullable = false)
    private String body;

    /** {@code true}, wenn {@link #body} HTML statt Plaintext ist. */
    @Column(name = "html")
    private boolean html;
}

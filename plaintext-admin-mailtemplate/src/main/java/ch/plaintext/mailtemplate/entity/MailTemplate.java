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
 * Admin-editable override for a system mail text (subject+body), identified by
 * {@code templateKey} (e.g. {@code auth.registration}). If there is no row for a key, the default
 * text stored in the code applies (the caller passes it directly to {@link
 * ch.plaintext.mailtemplate.service.MailTemplateService#render}) — no separate seed mechanism.
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

    /** {@code true} if {@link #body} is HTML instead of plain text. */
    @Column(name = "html")
    private boolean html;
}

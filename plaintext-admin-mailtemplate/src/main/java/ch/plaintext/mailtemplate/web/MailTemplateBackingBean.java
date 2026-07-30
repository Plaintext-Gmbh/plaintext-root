/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate.web;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.mailtemplate.entity.MailTemplate;
import ch.plaintext.mailtemplate.service.MailTemplateService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;

/**
 * Backing-Bean für {@code mailtemplates.xhtml}: Liste der Mailtext-Overrides des aktuellen
 * Mandanten, Anlegen/Ändern/Entfernen. Ohne Override gilt der Code-Default (siehe
 * {@link MailTemplateService#render}).
 */
@Component("mailTemplateBean")
@Scope("session")
@Data
@Slf4j
public class MailTemplateBackingBean implements Serializable {

    @Autowired
    private transient MailTemplateService mailTemplateService;

    private List<MailTemplate> templates;

    // Formular: Override anlegen/ändern
    private String templateKey;
    private String betreff;
    private String body;
    private boolean html;

    @PostConstruct
    public void onLoad() {
        refresh();
    }

    public void refresh() {
        templates = mailTemplateService.getOverrides(PlaintextSecurityHolder.getMandat());
    }

    /** Legt einen Override an oder aktualisiert ihn (Zuordnung über templateKey). */
    public void save() {
        try {
            if (templateKey == null || templateKey.isBlank()) {
                warn("Template-Key erforderlich.");
                return;
            }
            if (betreff == null || betreff.isBlank() || body == null || body.isBlank()) {
                warn("Betreff und Body erforderlich.");
                return;
            }
            mailTemplateService.save(PlaintextSecurityHolder.getMandat(), templateKey.trim(), betreff, body, html);
            info("Mailtext '" + templateKey.trim() + "' gespeichert.");
            resetForm();
            refresh();
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    public void delete(MailTemplate entry) {
        try {
            mailTemplateService.deleteOverride(entry.getId());
            info("Override '" + entry.getTemplateKey() + "' entfernt — Code-Default gilt wieder.");
            refresh();
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    /** Lädt einen bestehenden Override ins Formular, um ihn zu ändern. */
    public void edit(MailTemplate entry) {
        templateKey = entry.getTemplateKey();
        betreff = entry.getBetreff();
        body = entry.getBody();
        html = entry.isHtml();
        info("Override '" + entry.getTemplateKey() + "' geladen — ändern und speichern.");
    }

    private void resetForm() {
        templateKey = null;
        betreff = null;
        body = null;
        html = false;
    }

    private void info(String m) {
        msg(FacesMessage.SEVERITY_INFO, "Mailtexte", m);
    }

    private void warn(String m) {
        msg(FacesMessage.SEVERITY_WARN, "Mailtexte", m);
    }

    private void error(String m) {
        msg(FacesMessage.SEVERITY_ERROR, "Fehler", m);
    }

    private void msg(FacesMessage.Severity s, String t, String m) {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            ctx.addMessage(null, new FacesMessage(s, t, m));
        }
    }
}

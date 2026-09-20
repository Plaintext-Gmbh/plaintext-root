/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.mailtemplate.entity.MailTemplate;
import ch.plaintext.mailtemplate.service.MailTemplateService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;

/**
 * Backing Bean for {@code mailtemplates.xhtml}: list of the mail text overrides of the current
 * tenant, creating/changing/removing them. Without an override the code default applies (see
 * {@link MailTemplateService#render}).
 *
 * <h2>Why field injection stays here (java:S6813, card 1273)</h2>
 *
 * <p>This bean is <b>session-scoped and serializable</b> — the one case in which constructor
 * injection is the wrong answer. On a deserialization <b>no constructor runs</b>: a service
 * field set only through the
 * constructor stays {@code null} forever, and being {@code final} nothing can set it afterwards,
 * not even the context. A {@code NotSerializableException} would thereby turn into a permanent
 * {@code NullPointerException} (cards 915/1246). Field injection ({@code @Autowired}, not
 * {@code final}) lets the context refill the field after a deserialization — that is the house
 * rule, and {@code PlaintextSessionBeanSerialisierbarTest} enforces it as a build-breaking
 * guard.</p>
 *
 * <p>{@code java:S6813} demands the exact opposite at these fields, so both cannot hold at once.
 * The guard wins: it protects against a defect that is silent and permanent, the rule protects a
 * style. The suppression sits on the class because every injected service of such a bean falls
 * under the house rule — card 1273 carries the measurement and the decision.</p>
 */
@Component("mailTemplateBean")
@Scope("session")
@Data
@Slf4j
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
public class MailTemplateBackingBean implements Serializable {

    @Autowired
    private transient MailTemplateService mailTemplateService;

    private List<MailTemplate> templates;

    // Form: create/change an override
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

    /** Creates an override or updates it (matched via templateKey). */
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

    /** Loads an existing override into the form in order to change it. */
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
        FacesMessages.meldung(s, t, m);
    }
}

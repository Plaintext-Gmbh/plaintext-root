/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.web;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.webhooks.entity.WebhookDelivery;
import ch.plaintext.webhooks.entity.WebhookEndpoint;
import ch.plaintext.webhooks.service.WebhookEndpointService;
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
 * Backing-Bean für {@code webhooks.xhtml}: Endpoint-CRUD, Test-Ping, Delivery-Log je Endpoint.
 * Das Signing-Secret wird nach Anlage/Rotation einmalig im Klartext angezeigt ({@link #newSecret},
 * Muster wie {@code ApiTokenBackingBean}) und danach nie wieder ausgelesen.
 */
@Component("webhookBean")
@Scope("session")
@Data
@Slf4j
public class WebhookBackingBean implements Serializable {

    @Autowired
    private transient WebhookEndpointService endpointService;

    private List<WebhookEndpoint> endpoints;
    private List<WebhookDelivery> deliveries;
    private WebhookEndpoint logEndpoint;

    // Formular: Endpoint anlegen/ändern
    private Long editId;
    private String name;
    private String url;
    private boolean enabled = true;
    private String eventTypes;

    /** Klartext-Secret nach Anlage/Rotation — nur einmal sichtbar. */
    private String newSecret;
    private String newSecretEndpointName;

    @PostConstruct
    public void onLoad() {
        refresh();
    }

    public void refresh() {
        endpoints = endpointService.findAll(PlaintextSecurityHolder.getMandat());
    }

    /** Bekannte Event-Typen (MVP-Katalog) als Hilfetext für das Formular. */
    public String getBekannteEventTypes() {
        return "rechnung.created, rechnung.status_changed, member.created, "
                + "event.anmeldung.created, event.zahlung.updated";
    }

    public void save() {
        try {
            if (name == null || name.isBlank()) {
                warn("Name erforderlich.");
                return;
            }
            if (url == null || url.isBlank()) {
                warn("URL erforderlich.");
                return;
            }
            if (editId == null) {
                String secret = endpointService.create(
                        PlaintextSecurityHolder.getMandat(), name.trim(), url.trim(), enabled, normalisiere(eventTypes));
                newSecret = secret;
                newSecretEndpointName = name.trim();
                info("Webhook-Endpoint '" + name.trim() + "' angelegt.");
            } else {
                WebhookEndpoint endpoint = findEditTarget();
                if (endpoint == null) {
                    warn("Eintrag nicht mehr vorhanden — bitte neu laden.");
                    return;
                }
                endpoint.setName(name.trim());
                endpoint.setUrl(url.trim());
                endpoint.setEnabled(enabled);
                endpoint.setEventTypes(normalisiere(eventTypes));
                endpointService.update(endpoint);
                info("Webhook-Endpoint '" + name.trim() + "' aktualisiert.");
            }
            resetForm();
            refresh();
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    public void edit(WebhookEndpoint endpoint) {
        editId = endpoint.getId();
        name = endpoint.getName();
        url = endpoint.getUrl();
        enabled = endpoint.isEnabled();
        eventTypes = endpoint.getEventTypes();
    }

    public void delete(WebhookEndpoint endpoint) {
        try {
            endpointService.delete(endpoint);
            info("Webhook-Endpoint '" + endpoint.getName() + "' geloescht.");
            refresh();
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    public void rotateSecret(WebhookEndpoint endpoint) {
        try {
            newSecret = endpointService.rotateSecret(endpoint);
            newSecretEndpointName = endpoint.getName();
            info("Signing-Secret fuer '" + endpoint.getName() + "' rotiert.");
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    public void testPing(WebhookEndpoint endpoint) {
        try {
            WebhookDelivery delivery = endpointService.testPing(endpoint);
            if (delivery.getStatus() == ch.plaintext.webhooks.entity.WebhookDeliveryStatus.OK) {
                info("Test-Ping an '" + endpoint.getName() + "' erfolgreich (HTTP " + delivery.getHttpStatus() + ").");
            } else {
                warn("Test-Ping an '" + endpoint.getName() + "' fehlgeschlagen: " + delivery.getResponseSnippet());
            }
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    public void showDeliveryLog(WebhookEndpoint endpoint) {
        logEndpoint = endpoint;
        deliveries = endpointService.deliveryLog(endpoint.getId());
    }

    public void dismissNewSecret() {
        newSecret = null;
        newSecretEndpointName = null;
    }

    public boolean isHasNewSecret() {
        return newSecret != null;
    }

    private WebhookEndpoint findEditTarget() {
        return endpoints.stream().filter(e -> e.getId().equals(editId)).findFirst().orElse(null);
    }

    private void resetForm() {
        editId = null;
        name = null;
        url = null;
        enabled = true;
        eventTypes = null;
    }

    private static String normalisiere(String eventTypes) {
        if (eventTypes == null) {
            return "";
        }
        // Karte 458 (java:S5852): '\\s*,\\s*' kann bei langen Eingaben aus dem UI-Feld in
        // quadratisches Backtracking laufen. Trennen am Komma und einzeln trimmen ist linear.
        return java.util.Arrays.stream(eventTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private void info(String m) {
        msg(FacesMessage.SEVERITY_INFO, "Webhooks", m);
    }

    private void warn(String m) {
        msg(FacesMessage.SEVERITY_WARN, "Webhooks", m);
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

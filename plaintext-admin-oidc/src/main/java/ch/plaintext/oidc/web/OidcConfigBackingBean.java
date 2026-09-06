/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.oidc.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.oidc.entity.OidcConfig;
import ch.plaintext.oidc.service.OidcConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.file.UploadedFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component("oidcConfigBackingBean")
@Scope("session")
@Data
public class OidcConfigBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private transient OidcConfigService oidcConfigService;

    @Autowired
    private transient PlaintextSecurity plaintextSecurity;

    private List<OidcConfig> configs = new ArrayList<>();
    private OidcConfig selected;
    private String testResult;
    private transient UploadedFile uploadedFile;

    @PostConstruct
    public void init() {
        if (!isRoot()) {
            return;
        }
        loadData();
    }

    public void loadData() {
        configs = oidcConfigService.findAll();
        if (!configs.isEmpty() && selected == null) {
            selected = configs.getFirst();
        }
    }

    public boolean isRoot() {
        return plaintextSecurity != null && plaintextSecurity.ifGranted("ROLE_root");
    }

    public void checkAccess() {
        if (!isRoot()) {
            log.warn("SECURITY: User attempted to access OIDC config without ROLE_root");
            try {
                FacesContext.getCurrentInstance().getExternalContext().redirect("access-denied.xhtml");
            } catch (Exception e) {
                log.error("Error redirecting to access denied page", e);
            }
        }
    }

    public void newConfig() {
        log.info("OIDC: newConfig() called, creating new OidcConfig");
        selected = new OidcConfig();
        log.info("OIDC: selected is now: {}", selected);
    }

    public void select(OidcConfig config) {
        this.selected = config;
        this.testResult = null;
    }

    /**
     * Guard for interactions that require a selected entry. {@code selected} is {@code null} when
     * the session has expired or is new (session scope gone) — the user should then see a clear
     * message instead of a silent no-op (or even an NPE on save).
     *
     * @return true if an entry is selected; false if it was aborted (message set)
     */
    private boolean requireSelected() {
        if (selected != null) {
            return true;
        }
        FacesContext context = FacesContext.getCurrentInstance();
        FacesMessages.warn("Sitzung abgelaufen", "Sitzung abgelaufen — bitte Eintrag erneut auswählen.");
        context.validationFailed();
        return false;
    }

    public void save() {
        log.info("OIDC: save() called, selected={}", selected);
        if (!requireSelected()) {
            return;
        }
        FacesContext context = FacesContext.getCurrentInstance();

        if (selected.getIssuerUrl() == null || selected.getIssuerUrl().isBlank()) {
            FacesMessages.error("Fehler", "Issuer-URL darf nicht leer sein.");
            context.validationFailed();
            return;
        }

        if (selected.getClientId() == null || selected.getClientId().isBlank()) {
            FacesMessages.error("Fehler", "Client-ID darf nicht leer sein.");
            context.validationFailed();
            return;
        }

        selected = oidcConfigService.save(selected);
        loadData();
        FacesMessages.info("Erfolg", "OIDC-Konfiguration gespeichert.");
    }

    public void delete() {
        if (!requireSelected()) {
            return;
        }
        oidcConfigService.delete(selected);
        selected = null;
        loadData();
        FacesMessages.info("Erfolg", "OIDC-Konfiguration gelöscht.");
    }

    public void testConnection() {
        if (selected == null) {
            testResult = "Keine Konfiguration ausgewählt";
            return;
        }
        testResult = oidcConfigService.testConnection(selected);

        FacesMessage.Severity severity = "OK".equals(testResult)
                ? FacesMessage.SEVERITY_INFO
                : FacesMessage.SEVERITY_ERROR;
        String summary = "OK".equals(testResult) ? "Verbindung erfolgreich" : "Verbindung fehlgeschlagen";
        FacesMessages.meldung(severity, summary, testResult);
    }

    public void downloadJson() {
        if (selected == null) return;
        try {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", selected.getName());
            json.put("enabled", selected.isEnabled());
            json.put("issuerUrl", selected.getIssuerUrl());
            json.put("clientId", selected.getClientId());
            json.put("clientSecret", selected.getClientSecret());
            json.put("scopes", selected.getScopes());
            json.put("usernameAttribute", selected.getUsernameAttribute());
            json.put("buttonLabel", selected.getButtonLabel());
            json.put("buttonIcon", selected.getButtonIcon());
            json.put("autoCreateUsers", selected.isAutoCreateUsers());
            json.put("defaultRoles", selected.getDefaultRoles());
            json.put("defaultMandat", selected.getDefaultMandat());

            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            byte[] content = mapper.writeValueAsBytes(json);

            FacesContext fc = FacesContext.getCurrentInstance();
            ExternalContext ec = fc.getExternalContext();
            ec.responseReset();
            ec.setResponseContentType("application/json");
            ec.setResponseContentLength(content.length);
            String filename = "oidc-" + selected.getName().replaceAll("[^a-zA-Z0-9-]", "_") + ".json";
            ec.setResponseHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            OutputStream out = ec.getResponseOutputStream();
            out.write(content);
            out.flush();
            fc.responseComplete();
        } catch (Exception e) {
            log.error("Error downloading OIDC config as JSON", e);
            FacesMessages.error("Fehler", "Download fehlgeschlagen: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void uploadJson(FileUploadEvent event) {
        uploadedFile = event.getFile();
        if (uploadedFile == null || uploadedFile.getSize() == 0) {
            FacesMessages.warn("Hinweis", "Keine Datei ausgewählt.");
            return;
        }
        try (InputStream is = uploadedFile.getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> json = mapper.readValue(content, Map.class);

            if (selected == null) {
                selected = new OidcConfig();
            }
            if (json.containsKey("name")) selected.setName((String) json.get("name"));
            if (json.containsKey("enabled")) selected.setEnabled(Boolean.TRUE.equals(json.get("enabled")));
            if (json.containsKey("issuerUrl")) selected.setIssuerUrl((String) json.get("issuerUrl"));
            if (json.containsKey("clientId")) selected.setClientId((String) json.get("clientId"));
            if (json.containsKey("clientSecret")) selected.setClientSecret((String) json.get("clientSecret"));
            if (json.containsKey("scopes")) selected.setScopes((String) json.get("scopes"));
            if (json.containsKey("usernameAttribute")) selected.setUsernameAttribute((String) json.get("usernameAttribute"));
            if (json.containsKey("buttonLabel")) selected.setButtonLabel((String) json.get("buttonLabel"));
            if (json.containsKey("buttonIcon")) selected.setButtonIcon((String) json.get("buttonIcon"));
            if (json.containsKey("autoCreateUsers")) selected.setAutoCreateUsers(Boolean.TRUE.equals(json.get("autoCreateUsers")));
            if (json.containsKey("defaultRoles")) selected.setDefaultRoles((String) json.get("defaultRoles"));
            if (json.containsKey("defaultMandat")) selected.setDefaultMandat((String) json.get("defaultMandat"));

            FacesMessages.info("Erfolg", "JSON importiert - bitte prüfen und speichern.");
        } catch (Exception e) {
            log.error("Error uploading OIDC config JSON", e);
            FacesMessages.error("Fehler", "Import fehlgeschlagen: " + e.getMessage());
        }
    }

    public List<String> getScopesList() {
        if (selected == null || selected.getScopes() == null || selected.getScopes().isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(selected.getScopes().split(",")));
    }

    public void setScopesList(List<String> scopes) {
        if (selected != null) {
            selected.setScopes(scopes != null ? String.join(",", scopes) : "");
        }
    }
}

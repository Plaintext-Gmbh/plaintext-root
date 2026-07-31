/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.SystemMailAccount;
import ch.plaintext.SystemMailSender;
import ch.plaintext.settings.RootUserToggleEvent;
import ch.plaintext.settings.entity.BrandingLogo;
import ch.plaintext.settings.entity.SetupConfig;
import ch.plaintext.settings.service.BrandingService;
import ch.plaintext.settings.service.SetupConfigService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.model.SelectItem;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.file.UploadedFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
@Scope("session")
@Getter
@Setter
@Slf4j
public class SetupBackingBean implements Serializable {

    private final transient BrandingService brandingService;
    private final transient SetupConfigService setupConfigService;
    private final PlaintextSecurity security;
    private final transient ApplicationContext applicationContext;

    // Branding fields
    private String footerText;
    private boolean showVersion;
    private boolean showRootVersion;
    private boolean showBuildTimestamp;

    private Integer lightLogoWidth = 180;
    private Integer lightLogoHeight = 40;
    private Integer darkLogoWidth = 180;
    private Integer darkLogoHeight = 40;

    private boolean hasLightLogo;
    private boolean hasDarkLogo;
    private transient String lightLogoPreview;
    private transient String darkLogoPreview;

    private boolean i18nEnabled;
    private String i18nIcon;

    // Login settings fields
    private boolean oidcAutoRedirectEnabled;
    private Long oidcAutoRedirectConfigId;
    private boolean passwordManagementEnabled = true;
    // SECURITY (Karte 306): Vorbelegung fuer eine NEUE Setup-Konfiguration ist AUS; bestehende
    // Konfigurationen ueberschreiben dies beim Laden (config.isRootUserEnabled()).
    private boolean rootUserEnabled = false;
    private boolean selfRegistrationEnabled = false;
    private boolean passwordResetLinkEnabled = false;
    private boolean magicLinkEnabled = false;
    private boolean totpEnabled = false;
    private Long systemMailAccountId;

    /** Optional: von der App (plaintext-z-mailbox) geliefert; null, wenn die App/Mailbox nicht vorhanden ist. */
    @Autowired(required = false)
    private transient SystemMailSender systemMailSender;

    private boolean root;

    public SetupBackingBean(BrandingService brandingService, SetupConfigService setupConfigService,
                            PlaintextSecurity security, ApplicationContext applicationContext) {
        this.brandingService = brandingService;
        this.setupConfigService = setupConfigService;
        this.security = security;
        this.applicationContext = applicationContext;
    }

    /**
     * preRenderView-Listener (session-scoped statt @ViewScoped): setzt die Rolle, sperrt Nicht-ROOT aus
     * (Redirect) und lädt die Daten FRISCH bei jedem Seitenaufruf (GET). Der isPostback-Guard verhindert
     * das Neuladen bei jedem Ajax-Postback. Ersetzt das frühere @PostConstruct init() + checkAccess().
     */
    public void onLoad() {
        root = security.ifGranted("ROLE_ROOT");
        if (!root) {
            try {
                FacesContext.getCurrentInstance().getExternalContext().redirect("/index.xhtml");
            } catch (Exception e) {
                log.error("Redirect failed", e);
            }
            return;
        }
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        loadData();
    }

    private void loadData() {
        String mandat = security.getMandat();

        // Branding settings
        footerText = brandingService.getFooterText(mandat);
        showVersion = brandingService.isShowVersion(mandat);
        showRootVersion = brandingService.isShowRootVersion(mandat);
        showBuildTimestamp = brandingService.isShowBuildTimestamp(mandat);

        i18nEnabled = brandingService.isI18nEnabled(mandat);
        i18nIcon = brandingService.getI18nIcon(mandat);

        brandingService.getLogo(mandat, "light").ifPresent(logo -> {
            hasLightLogo = true;
            lightLogoWidth = logo.getLogoWidth();
            lightLogoHeight = logo.getLogoHeight();
            lightLogoPreview = buildDataUri(logo);
        });

        brandingService.getLogo(mandat, "dark").ifPresent(logo -> {
            hasDarkLogo = true;
            darkLogoWidth = logo.getLogoWidth();
            darkLogoHeight = logo.getLogoHeight();
            darkLogoPreview = buildDataUri(logo);
        });

        // Login settings
        setupConfigService.findByMandat(mandat).ifPresent(config -> {
            oidcAutoRedirectEnabled = config.isOidcAutoRedirectEnabled();
            oidcAutoRedirectConfigId = config.getOidcAutoRedirectConfigId();
            passwordManagementEnabled = config.isPasswordManagementEnabled();
            rootUserEnabled = config.isRootUserEnabled();
            selfRegistrationEnabled = config.isSelfRegistrationEnabled();
            passwordResetLinkEnabled = config.isPasswordResetLinkEnabled();
            magicLinkEnabled = config.isMagicLinkEnabled();
            totpEnabled = config.isTotpEnabled();
            systemMailAccountId = config.getSystemMailAccountId();
        });
    }

    // === Logo methods ===

    public void handleLightLogoUpload(FileUploadEvent event) {
        handleLogoUpload(event, "light");
    }

    public void handleDarkLogoUpload(FileUploadEvent event) {
        handleLogoUpload(event, "dark");
    }

    private void handleLogoUpload(FileUploadEvent event, String theme) {
        UploadedFile file = event.getFile();
        if (file == null || file.getContent() == null || file.getContent().length == 0) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Datei ausgewählt");
            return;
        }

        try {
            Integer width = "light".equals(theme) ? lightLogoWidth : darkLogoWidth;
            Integer height = "light".equals(theme) ? lightLogoHeight : darkLogoHeight;

            brandingService.saveLogo(
                    security.getMandat(), theme,
                    file.getContent(), file.getContentType(), file.getFileName(),
                    width, height
            );

            if ("light".equals(theme)) {
                hasLightLogo = true;
                lightLogoPreview = "data:" + file.getContentType() + ";base64,"
                        + Base64.getEncoder().encodeToString(file.getContent());
            } else {
                hasDarkLogo = true;
                darkLogoPreview = "data:" + file.getContentType() + ";base64,"
                        + Base64.getEncoder().encodeToString(file.getContent());
            }

            refreshBrandingBean();
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg",
                    (theme.equals("light") ? "Light" : "Dark") + "-Logo hochgeladen");
        } catch (IllegalArgumentException e) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", e.getMessage());
        } catch (Exception e) {
            log.error("Error uploading logo", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Upload fehlgeschlagen");
        }
    }

    public void deleteLightLogo() {
        try {
            brandingService.deleteLogo(security.getMandat(), "light");
            hasLightLogo = false;
            lightLogoPreview = null;
            lightLogoWidth = 180;
            lightLogoHeight = 40;
            refreshBrandingBean();
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Light-Logo gelöscht");
        } catch (Exception e) {
            log.error("Error deleting light logo", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Löschen fehlgeschlagen");
        }
    }

    public void deleteDarkLogo() {
        try {
            brandingService.deleteLogo(security.getMandat(), "dark");
            hasDarkLogo = false;
            darkLogoPreview = null;
            darkLogoWidth = 180;
            darkLogoHeight = 40;
            refreshBrandingBean();
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Dark-Logo gelöscht");
        } catch (Exception e) {
            log.error("Error deleting dark logo", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Löschen fehlgeschlagen");
        }
    }

    public void saveI18nSettings() {
        try {
            brandingService.saveI18nSettings(security.getMandat(), i18nEnabled, i18nIcon);
            refreshBrandingBean();
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Spracheinstellungen gespeichert");
        } catch (Exception e) {
            log.error("Error saving i18n settings", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Speichern fehlgeschlagen");
        }
    }

    public void saveFooterSettings() {
        try {
            brandingService.saveFooterSettings(security.getMandat(),
                    footerText, showVersion, showRootVersion, showBuildTimestamp);
            refreshBrandingBean();
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Footer-Einstellungen gespeichert");
        } catch (Exception e) {
            log.error("Error saving footer settings", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Speichern fehlgeschlagen");
        }
    }

    public void saveLightLogoDimensions() {
        saveDimensions("light", lightLogoWidth, lightLogoHeight);
    }

    public void saveDarkLogoDimensions() {
        saveDimensions("dark", darkLogoWidth, darkLogoHeight);
    }

    private void saveDimensions(String theme, Integer width, Integer height) {
        try {
            String mandat = security.getMandat();
            brandingService.getLogo(mandat, theme).ifPresent(logo -> {
                logo.setLogoWidth(width);
                logo.setLogoHeight(height);
                brandingService.updateLogoDimensions(logo);
            });
            refreshBrandingBean();
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Dimensionen gespeichert");
        } catch (Exception e) {
            log.error("Error saving logo dimensions", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Speichern fehlgeschlagen");
        }
    }

    // === Login settings ===

    public void saveLoginSettings() {
        try {
            String mandat = security.getMandat();
            SetupConfig config = setupConfigService.getOrCreate(mandat);
            boolean rootUserChanged = config.isRootUserEnabled() != rootUserEnabled;
            config.setOidcAutoRedirectEnabled(oidcAutoRedirectEnabled);
            config.setOidcAutoRedirectConfigId(oidcAutoRedirectConfigId);
            config.setPasswordManagementEnabled(passwordManagementEnabled);
            config.setRootUserEnabled(rootUserEnabled);
            config.setSelfRegistrationEnabled(selfRegistrationEnabled);
            config.setPasswordResetLinkEnabled(passwordResetLinkEnabled);
            config.setMagicLinkEnabled(magicLinkEnabled);
            config.setTotpEnabled(totpEnabled);
            config.setSystemMailAccountId(systemMailAccountId);
            setupConfigService.save(config);
            if (rootUserChanged) {
                applicationContext.publishEvent(new RootUserToggleEvent(this, rootUserEnabled));
            }
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Login-Einstellungen gespeichert");
        } catch (Exception e) {
            log.error("Error saving login settings", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Speichern fehlgeschlagen");
        }
    }

    /** Auswahl-Optionen der GLOBAL-Systemmailkonten (Wert = accountId); nur „Auswählen", wenn keines existiert. */
    public List<SelectItem> getSystemMailAccounts() {
        List<SelectItem> items = new ArrayList<>();
        items.add(new SelectItem(null, "-- Auswählen --"));
        if (systemMailSender != null) {
            for (SystemMailAccount a : systemMailSender.listGlobalAccounts()) {
                items.add(new SelectItem(a.id(), a.name() + " (" + a.emailAddress() + ")"));
            }
        }
        return items;
    }

    /** {@code true}, wenn mindestens ein GLOBAL-Systemmailkonto existiert (sonst Hinweis anzeigen). */
    public boolean isSystemMailAccountAvailable() {
        return systemMailSender != null && !systemMailSender.listGlobalAccounts().isEmpty();
    }

    // === Helpers ===

    private String buildDataUri(BrandingLogo logo) {
        return "data:" + logo.getContentType() + ";base64,"
                + logo.getImageData();
    }

    private void refreshBrandingBean() {
        try {
            Object brandingBean = applicationContext.getBean("brandingBean");
            Method refresh = brandingBean.getClass().getMethod("refresh");
            refresh.invoke(brandingBean);
        } catch (Exception e) {
            log.debug("Could not refresh BrandingBean: {}", e.getMessage());
        }
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }
}

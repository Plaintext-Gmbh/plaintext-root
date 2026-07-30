/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.ISettingsService;
import ch.plaintext.settings.entity.BrandingLogo;
import ch.plaintext.settings.repository.BrandingLogoRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class BrandingService {

    private static final String KEY_FOOTER_TEXT = "branding.footer.text";
    private static final String KEY_SHOW_VERSION = "branding.footer.showVersion";
    private static final String KEY_SHOW_ROOT_VERSION = "branding.footer.showRootVersion";
    private static final String KEY_SHOW_BUILD_TIMESTAMP = "branding.footer.showBuildTimestamp";
    private static final String KEY_I18N_ICON = "branding.i18n.icon";
    private static final String KEY_I18N_ENABLED = "branding.i18n.enabled";
    private static final String KEY_I18N_ENABLED_OLD = "i18n.enabled";

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/svg+xml", "image/webp", "image/jpeg"
    );
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // 2 MB

    private final BrandingLogoRepository logoRepository;
    private final ISettingsService settingsService;
    private final PlaintextSecurity security;

    public BrandingService(BrandingLogoRepository logoRepository,
                           ISettingsService settingsService,
                           PlaintextSecurity security) {
        this.logoRepository = logoRepository;
        this.settingsService = settingsService;
        this.security = security;
    }

    public Optional<BrandingLogo> getLogo(String mandat, String theme) {
        return logoRepository.findByMandatAndTheme(mandat, theme);
    }

    @Transactional
    public void saveLogo(String mandat, String theme, byte[] imageData,
                         String contentType, String fileName,
                         Integer width, Integer height) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Ungültiges Bildformat: " + contentType
                    + ". Erlaubt: PNG, SVG, WEBP, JPEG");
        }
        if (imageData.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Datei zu gross (max. 2 MB)");
        }

        BrandingLogo logo = logoRepository.findByMandatAndTheme(mandat, theme)
                .orElse(new BrandingLogo());
        logo.setMandat(mandat);
        logo.setTheme(theme);
        logo.setImageData(Base64.getEncoder().encodeToString(imageData));
        logo.setContentType(contentType);
        logo.setFileName(fileName);
        logo.setLogoWidth(width != null ? width : 180);
        logo.setLogoHeight(height != null ? height : 40);
        logoRepository.save(logo);
        log.info("Saved branding logo: mandat={}, theme={}, file={}", mandat, theme, fileName);
    }

    public byte[] getLogoBytes(BrandingLogo logo) {
        return Base64.getDecoder().decode(logo.getImageData());
    }

    @Transactional
    public void updateLogoDimensions(BrandingLogo logo) {
        logoRepository.save(logo);
    }

    @Transactional
    public void deleteLogo(String mandat, String theme) {
        logoRepository.deleteByMandatAndTheme(mandat, theme);
        log.info("Deleted branding logo: mandat={}, theme={}", mandat, theme);
    }

    public String getFooterText(String mandat) {
        String val = settingsService.getString(KEY_FOOTER_TEXT, mandat);
        return val != null ? val : "";
    }

    public boolean isShowVersion(String mandat) {
        Boolean val = settingsService.getBoolean(KEY_SHOW_VERSION, mandat);
        return val == null || val; // default true
    }

    public boolean isShowRootVersion(String mandat) {
        Boolean val = settingsService.getBoolean(KEY_SHOW_ROOT_VERSION, mandat);
        return val == null || val; // default true
    }

    public boolean isShowBuildTimestamp(String mandat) {
        Boolean val = settingsService.getBoolean(KEY_SHOW_BUILD_TIMESTAMP, mandat);
        return val == null || val; // default true
    }

    @Transactional
    public void saveFooterSettings(String mandat, String footerText,
                                   boolean showVersion, boolean showRootVersion,
                                   boolean showBuildTimestamp) {
        settingsService.setSetting(KEY_FOOTER_TEXT, mandat, footerText, "STRING", "Custom footer text");
        settingsService.setSetting(KEY_SHOW_VERSION, mandat, String.valueOf(showVersion), "BOOLEAN", "Show app version in footer");
        settingsService.setSetting(KEY_SHOW_ROOT_VERSION, mandat, String.valueOf(showRootVersion), "BOOLEAN", "Show root version in footer");
        settingsService.setSetting(KEY_SHOW_BUILD_TIMESTAMP, mandat, String.valueOf(showBuildTimestamp), "BOOLEAN", "Show build timestamp in footer");
        log.info("Saved branding footer settings: mandat={}", mandat);
    }

    public boolean isI18nEnabled(String mandat) {
        Boolean val = settingsService.getBoolean(KEY_I18N_ENABLED, mandat);
        if (val == null) {
            // Fallback: alten Key pruefen (Rueckwaertskompatibilitaet)
            val = settingsService.getBoolean(KEY_I18N_ENABLED_OLD, mandat);
        }
        return val == null || val;
    }

    public String getI18nIcon(String mandat) {
        String val = settingsService.getString(KEY_I18N_ICON, mandat);
        return val != null && !val.isBlank() ? val : "pi pi-globe";
    }

    @Transactional
    public void saveI18nSettings(String mandat, boolean enabled, String icon) {
        settingsService.setSetting(KEY_I18N_ENABLED, mandat, String.valueOf(enabled), "BOOLEAN", "Enable language switcher in topbar");
        settingsService.setSetting(KEY_I18N_ICON, mandat, icon, "STRING", "Icon class for language switcher");
        log.info("Saved i18n settings: mandat={}, enabled={}, icon={}", mandat, enabled, icon);
    }

    public Integer getLogoWidth(String mandat, String theme) {
        return getLogo(mandat, theme).map(BrandingLogo::getLogoWidth).orElse(180);
    }

    public Integer getLogoHeight(String mandat, String theme) {
        return getLogo(mandat, theme).map(BrandingLogo::getLogoHeight).orElse(40);
    }

    public boolean hasLogo(String mandat, String theme) {
        return logoRepository.findByMandatAndTheme(mandat, theme).isPresent();
    }
}

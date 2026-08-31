/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.ISettingsService;
import ch.plaintext.settings.SettingsKeys;
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

    private static final String KEY_APP_NAME = "branding.app.name";
    private static final String KEY_FOOTER_TEXT = "branding.footer.text";
    private static final String KEY_SHOW_VERSION = "branding.footer.showVersion";
    private static final String KEY_SHOW_ROOT_VERSION = "branding.footer.showRootVersion";
    private static final String KEY_SHOW_BUILD_TIMESTAMP = "branding.footer.showBuildTimestamp";
    private static final String KEY_I18N_ICON = "branding.i18n.icon";

    /**
     * SECURITY (card 314, item 14): {@code image/svg+xml} is NO longer allowed.
     *
     * <p>An SVG is an XML document and may contain {@code <script>} as well as event handlers.
     * The logo was served via {@code GET /api/branding/logo} same-origin and with exactly the
     * stored content type — an uploaded SVG was therefore stored XSS in the application origin.
     * A sanitizer (jsoup or similar) would be the more elaborate alternative; the raster formats
     * are enough for a logo, so SVG is dropped without a replacement.</p>
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/webp", "image/jpeg"
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
                    + ". Erlaubt: PNG, WEBP, JPEG");
        }
        if (imageData.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Datei zu gross (max. 2 MB)");
        }
        // SECURITY (card 314, item 14): the content type comes from the client and can be
        // chosen freely. Without a content check, an SVG (or HTML) could be uploaded declared
        // as "image/png" and later served same-origin. Therefore additionally check the
        // magic bytes against the claimed type.
        if (!matchesMagicBytes(contentType, imageData)) {
            throw new IllegalArgumentException("Dateiinhalt passt nicht zum Bildformat " + contentType
                    + ". Erlaubt: PNG, WEBP, JPEG");
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

    /**
     * Application name from the settings ({@code branding.app.name}); empty when not set.
     * Used among other things by the template's title default.
     */
    public String getAppName(String mandat) {
        return settingsService.getString(KEY_APP_NAME, mandat);
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
        Boolean val = settingsService.getBoolean(SettingsKeys.I18N_ENABLED, mandat);
        if (val == null) {
            // Fallback: check the old key (backwards compatibility)
            val = settingsService.getBoolean(SettingsKeys.I18N_ENABLED_LEGACY, mandat);
        }
        return val == null || val;
    }

    public String getI18nIcon(String mandat) {
        String val = settingsService.getString(KEY_I18N_ICON, mandat);
        return val != null && !val.isBlank() ? val : "pi pi-globe";
    }

    @Transactional
    public void saveI18nSettings(String mandat, boolean enabled, String icon) {
        settingsService.setSetting(SettingsKeys.I18N_ENABLED, mandat, String.valueOf(enabled), "BOOLEAN", "Enable language switcher in topbar");
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

    /**
     * SECURITY (card 314, item 14): checks the actual file content (magic bytes) against
     * the content type claimed by the client.
     *
     * @param contentType the claimed content type
     * @param data        the uploaded bytes
     * @return {@code true} if the content matches the type
     */
    static boolean matchesMagicBytes(String contentType, byte[] data) {
        if (data == null || data.length < 12) {
            return false;
        }
        return switch (contentType) {
            // 89 50 4E 47 0D 0A 1A 0A
            case "image/png" -> (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                    && (data[4] & 0xFF) == 0x0D && (data[5] & 0xFF) == 0x0A
                    && (data[6] & 0xFF) == 0x1A && (data[7] & 0xFF) == 0x0A;
            // FF D8 FF
            case "image/jpeg" -> (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
            // "RIFF" .... "WEBP"
            case "image/webp" -> data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                    && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
            default -> false;
        };
    }

    /**
     * SECURITY (card 314, item 14): may this stored content type be served?
     * Covers legacy data — SVG logos uploaded before this fix are still in the
     * database and must no longer be rendered same-origin.
     *
     * @param contentType the stored content type
     * @return {@code true} if serving it is allowed
     */
    public static boolean isDeliverableContentType(String contentType) {
        return contentType != null && ALLOWED_CONTENT_TYPES.contains(contentType);
    }
}

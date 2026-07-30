/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.service.BrandingService;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("brandingBean")
@Scope("application")
@ConditionalOnWebApplication
@Getter
@Slf4j
public class BrandingBean {

    private final BrandingService brandingService;
    private final PlaintextSecurity security;

    private String footerText;
    private boolean showVersion = true;
    private boolean showRootVersion = true;
    private boolean showBuildTimestamp = true;
    private boolean hasLightLogo;
    private boolean hasDarkLogo;
    private int lightLogoWidth = 180;
    private int lightLogoHeight = 40;
    private int darkLogoWidth = 180;
    private int darkLogoHeight = 40;
    private String i18nIcon = "pi pi-globe";

    public BrandingBean(BrandingService brandingService, PlaintextSecurity security) {
        this.brandingService = brandingService;
        this.security = security;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public void refresh() {
        try {
            String mandat = security.getMandat();
            if (mandat == null || "NO_AUTH".equals(mandat) || "NO_USER".equals(mandat) || "ERROR".equals(mandat)) {
                log.debug("Skipping branding refresh - no valid mandat");
                return;
            }

            footerText = brandingService.getFooterText(mandat);
            showVersion = brandingService.isShowVersion(mandat);
            showRootVersion = brandingService.isShowRootVersion(mandat);
            showBuildTimestamp = brandingService.isShowBuildTimestamp(mandat);
            showBuildTimestamp = brandingService.isShowBuildTimestamp(mandat);
            hasLightLogo = brandingService.hasLogo(mandat, "light");
            hasDarkLogo = brandingService.hasLogo(mandat, "dark");
            lightLogoWidth = brandingService.getLogoWidth(mandat, "light");
            lightLogoHeight = brandingService.getLogoHeight(mandat, "light");
            darkLogoWidth = brandingService.getLogoWidth(mandat, "dark");
            darkLogoHeight = brandingService.getLogoHeight(mandat, "dark");
            i18nIcon = brandingService.getI18nIcon(mandat);

            log.debug("Branding refreshed for mandat={}: hasLightLogo={}, hasDarkLogo={}", mandat, hasLightLogo, hasDarkLogo);
        } catch (Exception e) {
            log.warn("Error refreshing branding: {}", e.getMessage());
        }
    }

    public boolean hasAnyLogo() {
        return hasLightLogo || hasDarkLogo;
    }

    public String getEffectiveFooterText() {
        return (footerText != null && !footerText.isBlank()) ? footerText : "Copyright \u00A9 Plaintext.ch";
    }
}

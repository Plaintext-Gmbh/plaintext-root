/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.oidc.entity.OidcConfig;
import ch.plaintext.oidc.service.OidcConfigService;
import ch.plaintext.settings.entity.SetupConfig;
import ch.plaintext.settings.service.SetupConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Optional;

@Component("loginSetupBean")
@Slf4j
public class LoginSetupBean implements Serializable {

    @Autowired
    private SetupConfigService setupConfigService;

    @Autowired(required = false)
    private OidcConfigService oidcConfigService;

    public boolean isOidcAutoRedirectActive() {
        try {
            Optional<SetupConfig> config = setupConfigService.findFirstWithOidcAutoRedirect();
            if (config.isEmpty()) {
                return false;
            }
            Long configId = config.get().getOidcAutoRedirectConfigId();
            if (configId == null || oidcConfigService == null) {
                return false;
            }
            return oidcConfigService.findAll().stream()
                    .anyMatch(c -> c.getId().equals(configId) && c.isEnabled());
        } catch (Exception e) {
            log.debug("Error checking OIDC auto-redirect: {}", e.getMessage());
            return false;
        }
    }

    public boolean isPasswordManagementActive() {
        try {
            return !setupConfigService.isPasswordManagementDisabledAnywhere();
        } catch (Exception e) {
            log.debug("Error checking password management: {}", e.getMessage());
            return true;
        }
    }

    public boolean isSelfRegistrationActive() {
        try {
            return setupConfigService.isSelfRegistrationEnabledAnywhere();
        } catch (Exception e) {
            log.debug("Error checking self-registration: {}", e.getMessage());
            return false;
        }
    }

    public boolean isPasswordResetLinkActive() {
        try {
            return setupConfigService.isPasswordResetLinkEnabledAnywhere();
        } catch (Exception e) {
            log.debug("Error checking password-reset link: {}", e.getMessage());
            return false;
        }
    }

    public boolean isMagicLinkActive() {
        try {
            return setupConfigService.isMagicLinkEnabledAnywhere();
        } catch (Exception e) {
            log.debug("Error checking magic-link: {}", e.getMessage());
            return false;
        }
    }

    public String getOidcAutoRedirectRegistrationId() {
        try {
            Optional<SetupConfig> config = setupConfigService.findFirstWithOidcAutoRedirect();
            if (config.isEmpty()) {
                return null;
            }
            Long configId = config.get().getOidcAutoRedirectConfigId();
            if (configId == null || oidcConfigService == null) {
                return null;
            }
            return oidcConfigService.findAll().stream()
                    .filter(c -> c.getId().equals(configId) && c.isEnabled())
                    .map(OidcConfig::getRegistrationId)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Error getting OIDC redirect registration ID: {}", e.getMessage());
            return null;
        }
    }
}

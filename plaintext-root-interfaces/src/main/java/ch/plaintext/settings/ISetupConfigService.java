/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings;

public interface ISetupConfigService {


    boolean isOidcAutoRedirectEnabled(String mandat);

    Long getOidcAutoRedirectConfigId(String mandat);

    boolean isPasswordManagementEnabled(String mandat);

    boolean isRootUserEnabled(String mandat);

    boolean isSelfRegistrationEnabled(String mandat);

    boolean isPasswordResetLinkEnabled(String mandat);

    boolean isMagicLinkEnabled(String mandat);

    /**
     * Whether session information (table {@code USER_SESSION}) is recorded for this tenant
     * (Card 627). If no configuration exists, {@code true} applies: without a switch everything
     * was always recorded so far, and the existing behavior should not change silently.
     */
    boolean isSessionTrackingEnabled(String mandat);

    /**
     * Whether the TOTP/2FA feature has been enabled system-wide (in at least one tenant
     * configuration) through the Root → Setup UI. Complements the static property
     * {@code plaintext.security.totp.enabled}.
     */
    boolean isTotpEnabledAnywhere();

    /**
     * The system-wide configured GLOBAL system mail account for auth mails (password reset / login
     * link / registration), or {@code null} if none is selected. GLOBAL accounts are system-wide
     * (ROOT only) – hence without a tenant.
     */
    Long getSystemMailAccountId();
}

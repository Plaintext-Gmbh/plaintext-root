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
     * Ob das TOTP/2FA-Feature system-weit (in mindestens einer Mandanten-Konfiguration) über die
     * Root→Setup-UI aktiviert wurde. Ergänzt die statische Property {@code plaintext.security.totp.enabled}.
     */
    boolean isTotpEnabledAnywhere();

    /**
     * Das system-weit konfigurierte GLOBAL-Systemmailkonto für Auth-Mails (PW-Reset/Login-Link/Registrierung),
     * oder {@code null}, wenn keines gewählt ist. GLOBAL-Konten sind systemweit (nur ROOT) – daher ohne Mandant.
     */
    Long getSystemMailAccountId();
}

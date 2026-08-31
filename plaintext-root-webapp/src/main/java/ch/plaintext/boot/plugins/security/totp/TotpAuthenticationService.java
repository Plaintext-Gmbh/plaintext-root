/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.totp;

import ch.plaintext.boot.plugins.security.PlaintextSecurityProperties;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Link between the pure {@link TotpService} and the user persistence. Encapsulates the
 * security-relevant decisions of the second factor against the concrete
 * {@link MyUserEntity}.
 *
 * <p><b>Security invariant (no bypass):</b> {@link #isTotpRequired(String)} decides
 * solely on the basis of (a) a globally activated feature and (b) {@code totpEnabled=true}
 * set on the user whether a second step is needed. The two-step gate in the
 * success handler/controller relies exclusively on this method - this way an
 * attacker with the correct password cannot bypass the second factor once the user has
 * activated it.
 *
 * <p>Recovery codes are redeemed <b>atomically/one-time</b>: the transactional removal of the
 * matched hash from the stored set (with a subsequent persist) ensures
 * that a code is invalid after having been used once.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TotpAuthenticationService {

    private final TotpService totpService;
    private final MyUserRepository userRepository;
    private final PlaintextSecurityProperties securityProperties;
    private final ISetupConfigService setupConfigService;

    /**
     * Whether the TOTP feature is globally armed. Active as soon as EITHER the static property
     * ({@code plaintext.security.totp.enabled}) OR the DB-backed flag set through the root→setup UI
     * ({@link ISetupConfigService#isTotpEnabledAnywhere()}) is set. The default stays {@code false}
     * if neither the property nor the DB flag is active (PROD-safe).
     */
    public boolean isFeatureEnabled() {
        return securityProperties.getTotp().isEnabled() || setupConfigService.isTotpEnabledAnywhere();
    }

    /**
     * Whether a second factor has to be demanded for {@code username} after a successful password
     * login. Only if the feature is globally active AND the user has set up/activated TOTP
     * themselves. OIDC-only users ({@code passwordless}) never have
     * {@code totpEnabled=true} and are therefore exempt.
     */
    @Transactional(readOnly = true)
    public boolean isTotpRequired(String username) {
        if (!isFeatureEnabled() || username == null) {
            return false;
        }
        MyUserEntity user = userRepository.findByUsername(username);
        return user != null && user.isTotpEnabled()
                && user.getTotpSecret() != null && !user.getTotpSecret().isBlank();
    }

    /**
     * Checks a value entered during the two-step login against the user:
     * first as a 6-digit TOTP code, then (fallback) as a recovery code. A successfully
     * used recovery code is consumed immediately (one-time).
     *
     * @return {@code true} on a valid TOTP code OR a valid, now redeemed recovery code.
     */
    @Transactional
    public boolean verifySecondFactor(String username, String input) {
        if (username == null || input == null || input.isBlank()) {
            return false;
        }
        MyUserEntity user = userRepository.findByUsername(username);
        if (user == null || !user.isTotpEnabled() || user.getTotpSecret() == null) {
            return false;
        }
        String trimmed = input.trim();

        // 1) Normal 6-digit TOTP code (time-window tolerant).
        if (totpService.verifyCode(user.getTotpSecret(), trimmed)) {
            return true;
        }

        // 2) Fallback: recovery code (redeemed atomically). Case- and hyphen-tolerant.
        return redeemRecoveryCode(user, trimmed);
    }

    /**
     * Redeems a recovery code: removes the matching hash from the stored set and
     * persists. One-time - after success the same code is invalid.
     */
    private boolean redeemRecoveryCode(MyUserEntity user, String input) {
        Set<String> stored = user.getRecoveryCodes();
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        String candidateHash = totpService.hashRecoveryCode(input);
        if (stored.remove(candidateHash)) {
            user.setRecoveryCodes(stored);
            userRepository.save(user);
            log.info("TOTP: Recovery-Code fuer '{}' eingeloest; {} verbleibend",
                    user.getUsername(), stored.size());
            return true;
        }
        return false;
    }

    /**
     * Completes the setup: verifies the confirmation code against the (not yet
     * activated) secret, activates 2FA on success and stores the secret + hashed
     * recovery codes. Returns the clear-text recovery codes for a one-time display.
     *
     * @return clear-text recovery codes on success, or {@code null} on an invalid code.
     */
    @Transactional
    public java.util.List<String> confirmAndEnable(String username, String pendingSecret, String confirmationCode) {
        if (username == null || pendingSecret == null || pendingSecret.isBlank()) {
            return null; // NOSONAR(S1168) null = error sentinel: the caller distinguishes null (invalid code) from non-null (success with recovery codes); List.of() would falsely signal success
        }
        MyUserEntity user = userRepository.findByUsername(username);
        if (user == null || user.isPasswordless()) {
            return null; // NOSONAR(S1168) null = error sentinel: the caller distinguishes null (invalid code) from non-null (success with recovery codes); List.of() would falsely signal success
        }
        if (!totpService.verifyCode(pendingSecret, confirmationCode)) {
            return null; // NOSONAR(S1168) null = error sentinel: the caller distinguishes null (invalid code) from non-null (success with recovery codes); List.of() would falsely signal success
        }
        java.util.List<String> plaintextCodes = totpService.generateRecoveryCodes();
        user.setTotpSecret(pendingSecret);
        user.setTotpEnabled(true);
        user.setRecoveryCodes(totpService.hashRecoveryCodes(plaintextCodes));
        userRepository.save(user);
        log.info("TOTP: 2FA fuer '{}' aktiviert ({} Recovery-Codes erzeugt)",
                username, plaintextCodes.size());
        return plaintextCodes;
    }

    /**
     * Deactivates 2FA for the user (secret + recovery codes are deleted).
     * The caller (self-service bean) has confirmed the password beforehand.
     */
    @Transactional
    public void disable(String username) {
        if (username == null) {
            return;
        }
        MyUserEntity user = userRepository.findByUsername(username);
        if (user == null) {
            return;
        }
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        user.setRecoveryCodes(new java.util.HashSet<>());
        userRepository.save(user);
        log.info("TOTP: 2FA fuer '{}' deaktiviert", username);
    }

    /** Whether the user currently has 2FA activated. */
    @Transactional(readOnly = true)
    public boolean isEnabledForUser(String username) {
        if (username == null) {
            return false;
        }
        MyUserEntity user = userRepository.findByUsername(username);
        return user != null && user.isTotpEnabled();
    }
}

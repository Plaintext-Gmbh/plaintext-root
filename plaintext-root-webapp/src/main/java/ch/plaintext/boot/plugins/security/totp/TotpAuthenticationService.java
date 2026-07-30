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
 * Bindeglied zwischen dem reinen {@link TotpService} und der User-Persistenz. Kapselt die
 * sicherheitsrelevanten Entscheidungen des zweiten Faktors gegen die konkrete
 * {@link MyUserEntity}.
 *
 * <p><b>Sicherheits-Invariante (kein Bypass):</b> {@link #isTotpRequired(String)} entscheidet
 * allein anhand von (a) global aktiviertem Feature und (b) am User gesetztem
 * {@code totpEnabled=true}, ob ein zweiter Schritt noetig ist. Der Zwei-Schritt-Gate im
 * SuccessHandler/Controller stuetzt sich ausschliesslich auf diese Methode – so kann ein
 * Angreifer mit korrektem Passwort den zweiten Faktor nicht umgehen, sobald der User ihn
 * aktiviert hat.
 *
 * <p>Recovery-Codes werden <b>atomar/one-time</b> eingeloest: das transaktionale Entfernen des
 * getroffenen Hashes aus dem gespeicherten Set (mit anschliessendem Persist) stellt sicher,
 * dass ein Code nach einmaliger Nutzung ungueltig ist.
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
     * Ob das TOTP-Feature global scharf ist. Aktiv, sobald ENTWEDER die statische Property
     * ({@code plaintext.security.totp.enabled}) ODER das über die Root→Setup-UI DB-gestützte Flag
     * ({@link ISetupConfigService#isTotpEnabledAnywhere()}) gesetzt ist. Default bleibt {@code false},
     * wenn weder Property noch DB-Flag aktiv sind (PROD-sicher).
     */
    public boolean isFeatureEnabled() {
        return securityProperties.getTotp().isEnabled() || setupConfigService.isTotpEnabledAnywhere();
    }

    /**
     * Ob fuer {@code username} nach erfolgreichem Passwort-Login ein zweiter Faktor
     * verlangt werden muss. Nur dann, wenn das Feature global aktiv ist UND der User TOTP
     * selbst eingerichtet/aktiviert hat. OIDC-only-User ({@code passwordless}) haben nie
     * {@code totpEnabled=true} und sind damit ausgenommen.
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
     * Prueft einen bei der Zwei-Schritt-Anmeldung eingegebenen Wert gegen den User:
     * erst als 6-stelliger TOTP-Code, dann (Fallback) als Recovery-Code. Ein erfolgreich
     * verwendeter Recovery-Code wird sofort verbraucht (one-time).
     *
     * @return {@code true} bei gueltigem TOTP-Code ODER gueltigem, nun eingeloestem Recovery-Code.
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

        // 1) Normaler 6-stelliger TOTP-Code (zeitfenster-tolerant).
        if (totpService.verifyCode(user.getTotpSecret(), trimmed)) {
            return true;
        }

        // 2) Fallback: Recovery-Code (atomar einloesen). Klein-/Gross- und Bindestrich-tolerant.
        return redeemRecoveryCode(user, trimmed);
    }

    /**
     * Loest einen Recovery-Code ein: entfernt den passenden Hash aus dem gespeicherten Set und
     * persistiert. One-time – nach Erfolg ist derselbe Code ungueltig.
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
     * Schliesst die Einrichtung ab: verifiziert den Bestaetigungscode gegen das (noch nicht
     * aktivierte) Secret, aktiviert bei Erfolg 2FA und speichert Secret + gehashte
     * Recovery-Codes. Gibt die Klartext-Recovery-Codes zur einmaligen Anzeige zurueck.
     *
     * @return Klartext-Recovery-Codes bei Erfolg, oder {@code null} bei ungueltigem Code.
     */
    @Transactional
    public java.util.List<String> confirmAndEnable(String username, String pendingSecret, String confirmationCode) {
        if (username == null || pendingSecret == null || pendingSecret.isBlank()) {
            return null; // NOSONAR(S1168) null = Fehler-Sentinel: der Aufrufer unterscheidet null (ungueltiger Code) von nicht-null (Erfolg mit Recovery-Codes); List.of() wuerde faelschlich Erfolg signalisieren
        }
        MyUserEntity user = userRepository.findByUsername(username);
        if (user == null || user.isPasswordless()) {
            return null; // NOSONAR(S1168) null = Fehler-Sentinel: der Aufrufer unterscheidet null (ungueltiger Code) von nicht-null (Erfolg mit Recovery-Codes); List.of() wuerde faelschlich Erfolg signalisieren
        }
        if (!totpService.verifyCode(pendingSecret, confirmationCode)) {
            return null; // NOSONAR(S1168) null = Fehler-Sentinel: der Aufrufer unterscheidet null (ungueltiger Code) von nicht-null (Erfolg mit Recovery-Codes); List.of() wuerde faelschlich Erfolg signalisieren
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
     * Deaktiviert 2FA fuer den User (Secret + Recovery-Codes werden geloescht).
     * Der Aufrufer (Self-Service-Bean) hat zuvor das Passwort bestaetigt.
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

    /** Ob der User aktuell 2FA aktiviert hat. */
    @Transactional(readOnly = true)
    public boolean isEnabledForUser(String username) {
        if (username == null) {
            return false;
        }
        MyUserEntity user = userRepository.findByUsername(username);
        return user != null && user.isTotpEnabled();
    }
}

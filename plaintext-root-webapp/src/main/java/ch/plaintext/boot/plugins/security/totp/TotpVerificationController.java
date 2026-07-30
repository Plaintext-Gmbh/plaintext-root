/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.totp;

import ch.plaintext.boot.plugins.security.PlaintextAuthenticationSuccessHandler;
import ch.plaintext.boot.plugins.security.lockout.AccountLockoutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

/**
 * Zweiter Schritt der Anmeldung: nimmt den 6-stelligen TOTP-Code (oder einen Recovery-Code)
 * entgegen und finalisiert die zuvor zurueckgehaltene Authentication.
 *
 * <h2>Sicherheits-Invariante (kein Bypass)</h2>
 * <ul>
 *   <li>Ohne "pending" Session-Zustand (den nur der {@link PlaintextAuthenticationSuccessHandler}
 *       nach korrektem Passwort setzt) ist jede Aktion hier wirkungslos → Redirect auf Login.
 *       Ein Angreifer kann {@code /login/totp} also nicht "kalt" aufrufen, um sich anzumelden.</li>
 *   <li>Die volle Authentication wird erst NACH gueltigem Code in den {@link SecurityContext}
 *       gesetzt und persistiert. Vorher ist der Request-Kontext anonym.</li>
 *   <li>Fehlversuche werden per {@link AccountLockoutService} gezaehlt (Brute-Force-Schutz auf
 *       den zweiten Faktor); ein Lockout invalidiert den pending-Zustand.</li>
 * </ul>
 */
@Controller
@Slf4j
@RequiredArgsConstructor
public class TotpVerificationController {

    static final String STEP_PATH = "/login/totp";

    private final TotpAuthenticationService totpAuthenticationService;
    private final AccountLockoutService lockoutService;
    private final SecurityContextRepository securityContextRepository;
    private final PlaintextAuthenticationSuccessHandler successHandler;

    /**
     * Rendert die Code-Eingabeseite. Nur erreichbar, wenn ein pending-Zustand existiert –
     * sonst zurueck zum Login (kein Anhaltspunkt fuer Angreifer, ob ein User existiert).
     */
    @GetMapping(STEP_PATH)
    public String showTotpPage(HttpServletRequest request) {
        TotpPendingAuthentication pending = readPending(request.getSession(false));
        if (pending == null) {
            return "redirect:/login.html";
        }
        // Facelet unter META-INF/resources/login-totp.xhtml
        return "forward:/login-totp.xhtml";
    }

    /**
     * Verifiziert den zweiten Faktor. Bei Erfolg: volle Authentication setzen + Redirect aufs
     * urspruengliche Ziel. Bei Fehler: zurueck mit Fehler-Flag (Rate-Limit greift).
     */
    @PostMapping(STEP_PATH)
    public void verify(@RequestParam(name = "code", required = false) String code,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        TotpPendingAuthentication pending = readPending(session);
        if (pending == null) {
            // Kein legitimer Zwischenzustand – nichts finalisieren.
            response.sendRedirect(request.getContextPath() + "/login.html");
            return;
        }

        String username = pending.username();
        // Eigener Lockout-Schluessel fuer den ZWEITEN Faktor: TOTP-Fehlversuche duerfen NICHT den
        // Passwort-Login (Schluessel = username) sperren – sonst wuerde ein vertippter Code den
        // legitimen User aus BEIDEN Faktoren aussperren. Der zweite Faktor hat seine eigene,
        // getrennte Brute-Force-Zaehlung.
        String totpLockKey = "totp:" + username;

        // Brute-Force-Schutz auf dem zweiten Faktor (getrennt vom Passwort-Faktor).
        if (lockoutService.isLocked(totpLockKey)) {
            invalidatePending(session);
            log.warn("TOTP: user '{}' locked out during second factor", username);
            response.sendRedirect(request.getContextPath() + "/login.html?error=totp_locked");
            return;
        }

        boolean ok = totpAuthenticationService.verifySecondFactor(username, code);
        if (!ok) {
            lockoutService.recordFailure(totpLockKey);
            log.info("TOTP: invalid second factor for user '{}'", username);
            response.sendRedirect(request.getContextPath() + STEP_PATH + "?error=totp_invalid");
            return;
        }

        // Erfolg: TOTP-Fehlerzaehler zuruecksetzen, pending verbrauchen und volle Auth herstellen.
        lockoutService.recordSuccess(totpLockKey);
        Authentication authentication = pending.authentication();
        String targetUrl = pending.targetUrl();
        invalidatePending(session);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // Login-Event erst jetzt publizieren – Login ist nun vollstaendig.
        successHandler.publishLoginEvent(request, authentication);

        log.debug("TOTP: second factor OK for user '{}', redirect to {}", username, targetUrl);
        response.sendRedirect(targetUrl != null ? targetUrl : request.getContextPath() + "/index.html");
    }

    private TotpPendingAuthentication readPending(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object attr = session.getAttribute(TotpPendingAuthentication.SESSION_ATTRIBUTE);
        return (attr instanceof TotpPendingAuthentication p) ? p : null;
    }

    private void invalidatePending(HttpSession session) {
        if (session != null) {
            session.removeAttribute(TotpPendingAuthentication.SESSION_ATTRIBUTE);
        }
    }
}

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
 * Second step of the login: takes the 6-digit TOTP code (or a recovery code)
 * and finalizes the previously withheld authentication.
 *
 * <h2>Security invariant (no bypass)</h2>
 * <ul>
 *   <li>Without a "pending" session state (which only the {@link PlaintextAuthenticationSuccessHandler}
 *       sets after a correct password) every action here has no effect → redirect to the login.
 *       An attacker therefore cannot call {@code /login/totp} "cold" in order to log in.</li>
 *   <li>The full authentication is only put into the {@link SecurityContext} and persisted
 *       AFTER a valid code. Before that the request context is anonymous.</li>
 *   <li>Failed attempts are counted via {@link AccountLockoutService} (brute-force protection on
 *       the second factor); a lockout invalidates the pending state.</li>
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
     * Renders the code entry page. Only reachable if a pending state exists -
     * otherwise back to the login (no clue for attackers whether a user exists).
     */
    @GetMapping(STEP_PATH)
    public String showTotpPage(HttpServletRequest request) {
        TotpPendingAuthentication pending = readPending(request.getSession(false));
        if (pending == null) {
            return "redirect:/login.html";
        }
        // facelet under META-INF/resources/login-totp.xhtml
        return "forward:/login-totp.xhtml";
    }

    /**
     * Verifies the second factor. On success: set the full authentication + redirect to the
     * original target. On failure: back with an error flag (the rate limit applies).
     */
    @PostMapping(STEP_PATH)
    public void verify(@RequestParam(name = "code", required = false) String code,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        TotpPendingAuthentication pending = readPending(session);
        if (pending == null) {
            // No legitimate intermediate state - do not finalize anything.
            response.sendRedirect(request.getContextPath() + "/login.html");
            return;
        }

        String username = pending.username();
        // Separate lockout key for the SECOND factor: TOTP failures must NOT lock the
        // password login (key = username) - otherwise a mistyped code would lock the
        // legitimate user out of BOTH factors. The second factor has its own,
        // separate brute-force counting.
        String totpLockKey = "totp:" + username;

        // Brute-force protection on the second factor (separate from the password factor).
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

        // Success: reset the TOTP failure counter, consume the pending state and establish the full auth.
        lockoutService.recordSuccess(totpLockKey);
        Authentication authentication = pending.authentication();
        String targetUrl = pending.targetUrl();
        invalidatePending(session);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // Only publish the login event now - the login is complete at this point.
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

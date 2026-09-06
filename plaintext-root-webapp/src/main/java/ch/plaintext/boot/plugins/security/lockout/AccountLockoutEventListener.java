/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.lockout;

import ch.plaintext.boot.plugins.log.Log;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Bridges Spring Security authentication events into
 * {@link AccountLockoutService}.
 *
 * <p>Specifically: bad-credentials events bump the failure counter,
 * successful authentication events clear it. Other failure flavours
 * (LockedException, DisabledException, …) are intentionally ignored —
 * a lockout-induced failure must not increment the counter again.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountLockoutEventListener {

    private final AccountLockoutService lockoutService;

    @EventListener
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        String username = extractUsername(event.getAuthentication());
        if (username != null) {
            lockoutService.recordFailure(username);
            if (log.isDebugEnabled()) {
                log.debug("Recorded bad-credentials failure for username '{}'", Log.mail(username));
            }
        }
    }

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        String username = extractUsername(event.getAuthentication());
        if (username != null) {
            lockoutService.recordSuccess(username);
        }
    }

    private static String extractUsername(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof String s) {
            return s;
        }
        return authentication.getName();
    }
}

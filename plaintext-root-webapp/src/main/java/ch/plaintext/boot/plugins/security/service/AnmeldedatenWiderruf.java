/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.service;

import ch.plaintext.boot.plugins.log.Log;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.sessions.service.HttpSessionRegistry;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * "Credentials changed": ends every existing login of an account except the calling session.
 *
 * <p><b>Why (card 1495, S-5 from review 1449):</b> only the password reset did this (card 314
 * item 9). A password change in "Mein Konto", a password set by an admin and switching on 2FA
 * left the remember-me tokens ({@code persistent_logins}) and the open sessions alive. Whoever
 * had captured the password kept a 7-day rolling remember-me cookie and stayed logged in without
 * the second factor. All four paths now call this one place.
 *
 * <p>The session the change was made from survives — the user is not thrown out of the page they
 * just used. Its remember-me token is deleted as well; the next login issues a fresh one (or none,
 * with 2FA on: see the TOTP gate in {@code PlaintextAuthenticationSuccessHandler}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnmeldedatenWiderruf {

    private final MyRememberMeRepository rememberMeRepository;

    /** Optional: the module plaintext-admin-sessions may be missing from a context. */
    private final ObjectProvider<HttpSessionRegistry> sessionRegistryProvider;

    /**
     * Deletes all remember-me tokens of {@code username} and invalidates all of its sessions except
     * the one of the current request (if there is one).
     */
    public void widerrufe(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        rememberMeRepository.deleteAllByUsername(username);

        HttpSessionRegistry sessionRegistry = sessionRegistryProvider.getIfAvailable();
        if (sessionRegistry == null) {
            log.warn("Session-Registry nicht verfuegbar — aktive Sessions von '{}' konnten nach der "
                    + "Aenderung der Anmeldedaten nicht invalidiert werden.", Log.mail(username));
            return;
        }
        // getSession(false): the anonymous reset request must not get a session just for this.
        HttpSession eigene = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest().getSession(false) : null;
        String eigeneSession = eigene == null ? null : eigene.getId();
        sessionRegistry.invalidateSessionsOfUser(username, eigeneSession);
    }
}

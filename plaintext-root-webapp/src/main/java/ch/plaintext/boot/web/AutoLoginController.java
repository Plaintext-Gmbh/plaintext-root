/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.plugins.security.SessionLoginFinalizer;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Anmeldung ueber einen statischen Autologin-Key ({@code GET /autologin?key=}).
 *
 * <p><b>Karte 309:</b> Der Controller baute die Session frueher selbst zusammen und umging damit
 * Session-Erneuerung, Account-Lockout und das TOTP-Gate. Der Session-Aufbau laeuft jetzt ueber
 * {@link SessionLoginFinalizer}, also ueber dieselben Komponenten wie der Form-Login. Der Weg als
 * solcher bleibt bestehen — er wird produktiv genutzt (PageTester/Playwright/ZAP, {@code MAD_AUTOLOGIN}
 * in mehreren Deploy-Umgebungen, Autologin-Links). Der Rueckbau des Endpunkts ist Sache der Karte
 * „autologin-endpoint-vollrueckbau" und braucht vorher die Umstellung dieser Konsumenten.</p>
 */
@Controller
@Slf4j
public class AutoLoginController {

    private final UserDetailsService userDetailsService;
    private final MyUserRepository userRepository;
    private final ISetupConfigService setupConfigService;
    private final SessionLoginFinalizer sessionLoginFinalizer;

    public AutoLoginController(UserDetailsService userDetailsService,
                               MyUserRepository userRepository,
                               ISetupConfigService setupConfigService,
                               SessionLoginFinalizer sessionLoginFinalizer) {
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.setupConfigService = setupConfigService;
        this.sessionLoginFinalizer = sessionLoginFinalizer;
    }

    /** Maskiert den Autologin-Key fuers Logging (keine Klartext-Keys in Logs/Graylog). */
    private static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "(leer)";
        }
        return key.length() <= 4 ? "****" : key.substring(0, 4) + "… (len " + key.length() + ")";
    }

    @GetMapping("/autologin")
    public String autoLogin(@RequestParam(required = false) String key,
                           HttpServletRequest request,
                           HttpServletResponse response) {

        // Der Key reist als URL-Query-Param (bestehende Aufrufer, siehe autologin-followups) --
        // zumindest Browser-/Zwischen-Caching der Antwort selbst verhindern.
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");

        log.info("AutoLogin requested with key: {}", mask(key));

        // Validate key is provided
        if (key == null || key.isEmpty()) {
            log.warn("No AutoLogin key provided");
            return "redirect:/login.html";
        }

        try {
            // Find user by autologinKey
            MyUserEntity user = userRepository.findByAutologinKey(key);

            if (user == null) {
                log.warn("No user found with AutoLogin key: {}", mask(key));
                return "redirect:/login.html";
            }

            // Load UserDetails from database
            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());

            // Check if autologin is enabled for this user's mandat
            String mandat = extractMandat(userDetails);
            if (!setupConfigService.isAutologinEnabled(mandat)) {
                log.warn("AutoLogin is disabled for mandat: {}", mandat);
                return "redirect:/login.html";
            }

            // Karte 309: Account-Status (Lockout!), Session-Erneuerung, 2FA-Gate, Startseite und
            // Login-Event laufen jetzt zentral -- exakt wie beim Form-Login. Der SuccessHandler
            // schreibt den Redirect selbst, deshalb hier kein View-Name mehr.
            sessionLoginFinalizer.finalizeLogin(userDetails, userDetails.getAuthorities(), "AutoLogin",
                    request, response);
            return null;

        } catch (AccountStatusException e) {
            // Gesperrt (Brute-Force-Lockout) oder deaktiviert -> derselbe Ausgang wie ein
            // fehlgeschlagener Form-Login.
            log.warn("AutoLogin abgelehnt (Account-Status): {}", e.getMessage());
            return "redirect:/login.html";
        } catch (Exception e) {
            log.error("AutoLogin failed", e);
            return "redirect:/login.html";
        }
    }

    private String extractMandat(UserDetails userDetails) {
        for (GrantedAuthority ga : userDetails.getAuthorities()) {
            String a = ga.getAuthority();
            if (a != null && a.startsWith("PROPERTY_MANDAT_")) {
                return a.substring("PROPERTY_MANDAT_".length()).toLowerCase();
            }
        }
        return "default";
    }
}

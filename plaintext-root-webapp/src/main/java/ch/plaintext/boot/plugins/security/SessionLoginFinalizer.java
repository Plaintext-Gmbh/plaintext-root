/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;

/**
 * Baut aus einem bereits geprueften {@link UserDetails} eine vollwertige Browser-Session — und zwar
 * <b>auf demselben Weg wie der Form-Login</b>.
 *
 * <p><b>Warum es diese Klasse gibt (Karte 309, Security-Audit 24.07.2026):</b> {@code AutoLoginController}
 * und {@code TokenLoginController} bauten den {@link SecurityContext} bisher jeweils selbst zusammen und
 * riefen direkt {@link SecurityContextRepository#saveContext}. Damit wurden gleich drei Schutzmechanismen
 * uebersprungen, die beim Form-/OAuth-/OTT-Login automatisch greifen:</p>
 * <ol>
 *   <li><b>Session-Fixation:</b> ohne {@link ChangeSessionIdAuthenticationStrategy} behaelt die Session
 *       ihre Id. Ein Angreifer, der dem Opfer vorab eine bekannte Session-Id unterschiebt, besitzt in
 *       dem Moment eine voll authentifizierte Session, in dem das Opfer seinen Auto-/Token-Login-Link
 *       oeffnet.</li>
 *   <li><b>Account-Lockout:</b> {@code MyUserDetailsService} setzt {@code accountNonLocked} anhand des
 *       {@code AccountLockoutService}, aber niemand wertete das Flag auf diesen Pfaden aus — ein wegen
 *       Brute-Force gesperrter Account blieb ueber Auto-/Token-Login offen.</li>
 *   <li><b>Zweiter Faktor (TOTP):</b> das 2FA-Gate sitzt im {@link PlaintextAuthenticationSuccessHandler},
 *       der nur an {@code formLogin}/{@code oauth2Login}/{@code oneTimeTokenLogin} verdrahtet ist. Wer
 *       die {@code Authentication} daran vorbei erzeugt, loggt TOTP-User ohne zweiten Faktor ein.</li>
 * </ol>
 *
 * <p>Die Loesung ist bewusst <em>kein</em> Nachbau der einzelnen Pruefungen, sondern die Delegation an
 * genau dieselben Komponenten: {@link AccountStatusUserDetailsChecker}, {@link SessionAuthenticationStrategy}
 * und {@link PlaintextAuthenticationSuccessHandler}. So kann kein kuenftiges Gate (z.B. der erzwungene
 * Passwortwechsel aus Karte 306) wieder nur an einem der Login-Wege haengen.</p>
 */
@Component
@Slf4j
public class SessionLoginFinalizer {

    private final SecurityContextRepository securityContextRepository;
    private final PlaintextAuthenticationSuccessHandler successHandler;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy = new ChangeSessionIdAuthenticationStrategy();
    private final UserDetailsChecker userDetailsChecker = new AccountStatusUserDetailsChecker();

    public SessionLoginFinalizer(SecurityContextRepository securityContextRepository,
                                 PlaintextAuthenticationSuccessHandler successHandler) {
        this.securityContextRepository = securityContextRepository;
        this.successHandler = successHandler;
    }

    /**
     * Prueft den Account-Status, erneuert die Session-Id, persistiert den {@link SecurityContext} und
     * uebergibt anschliessend an den {@link PlaintextAuthenticationSuccessHandler} (2FA-Gate, erzwungener
     * Passwortwechsel, Startseiten-Redirect, Login-Event).
     *
     * <p>Der Handler schreibt den Redirect selbst; der aufrufende Controller gibt danach {@code null}
     * zurueck (die Response ist dann bereits committed).</p>
     *
     * @param userDetails geprueftes Benutzerprofil (Herkunft: Form-Login bzw. ApiToken)
     * @param authorities Authorities, die die Session bekommen soll — als eigener Parameter, damit ein
     *                    Aufrufer sie kuenftig einschraenken kann, ohne die Session-Logik zu duplizieren
     * @param quelle      Kurzbezeichnung des Login-Wegs fuers Logging
     * @throws org.springframework.security.authentication.LockedException   Account gesperrt (Lockout)
     * @throws org.springframework.security.authentication.DisabledException Account deaktiviert
     */
    public void finalizeLogin(UserDetails userDetails,
                              Collection<? extends GrantedAuthority> authorities,
                              String quelle,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException, ServletException {

        // (1) Account-Status: gesperrt/deaktiviert/abgelaufen -> Exception, kein Login.
        userDetailsChecker.check(userDetails);

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities);

        // (2) Session-Fixation-Schutz: neue Session-Id, bevor der Context gespeichert wird.
        sessionAuthenticationStrategy.onAuthentication(authToken, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authToken);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        log.info("{}: Session aufgebaut fuer {} (Session-Id erneuert)", quelle, userDetails.getUsername());

        // (3) 2FA-Gate / Passwortwechsel / Startseite / Login-Event — identisch zum Form-Login.
        successHandler.onAuthenticationSuccess(request, response, authToken);
    }
}

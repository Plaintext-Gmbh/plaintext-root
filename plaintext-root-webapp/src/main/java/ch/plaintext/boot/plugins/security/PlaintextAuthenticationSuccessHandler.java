/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.StartpageResolver;
import ch.plaintext.boot.plugins.security.service.MyUserDetailsService;
import ch.plaintext.boot.plugins.security.totp.TotpAuthenticationService;
import ch.plaintext.boot.plugins.security.totp.TotpPendingAuthentication;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class PlaintextAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    /** Ziel der Zwei-Schritt-Anmeldung (TOTP-Code-Eingabe). */
    static final String TOTP_STEP_PATH = "/login/totp";

    /**
     * Selbstservice-Seite mit dem Passwort-Aenderungs-Formular. Ziel bei erzwungenem
     * Passwortwechsel (Karte 306), unabhaengig von einer konfigurierten Startseite.
     */
    static final String MUST_CHANGE_PASSWORD_PAGE = "myuser.xhtml";

    private final ApplicationEventPublisher eventPublisher;
    private final TotpAuthenticationService totpAuthenticationService;
    private final SecurityContextRepository securityContextRepository;
    private final PlaintextSecurityProperties securityProperties;
    /**
     * Zweifach genutzt: {@code loginSuccess(...)} stellt bei OAuth/OIDC das Remember-Me-Cookie aus
     * (siehe #166 – der Filter macht das nur beim Form-Login automatisch); als {@code LogoutHandler}
     * entfernt {@code logout(...)} im TOTP-Gate Cookie + DB-Token wieder (Bypass-Schutz, siehe unten).
     */
    private final PersistentTokenBasedRememberMeServices rememberMeServices;

    // @Lazy auf rememberMeServices: Die Bean stammt aus PlaintextSecurityConfig, das seinerseits
    // diesen SuccessHandler im Konstruktor braucht -> ohne @Lazy entstuende ein Bean-Zyklus.
    public PlaintextAuthenticationSuccessHandler(
            ApplicationEventPublisher eventPublisher,
            TotpAuthenticationService totpAuthenticationService,
            SecurityContextRepository securityContextRepository,
            PlaintextSecurityProperties securityProperties,
            @Lazy PersistentTokenBasedRememberMeServices rememberMeServices) {
        this.eventPublisher = eventPublisher;
        this.totpAuthenticationService = totpAuthenticationService;
        this.securityContextRepository = securityContextRepository;
        this.securityProperties = securityProperties;
        this.rememberMeServices = rememberMeServices;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                       HttpServletResponse response,
                                       Authentication authentication) throws IOException, ServletException {

        String userEmail = authentication.getName();
        Long userId = extractUserId(authentication);
        String mandat = extractMandat(authentication);

        // Abgesicherte Startseite: individuelle (gültige) startpage oder Fallback index.html –
        // niemand wird durch einen leeren/ungültigen Wert von der Startseite ausgesperrt.
        String page = StartpageResolver.resolve(authentication.getAuthorities());
        // Karte 306: Muss dieser User sein Passwort wechseln (z.B. Root-Initialpasswort), wird er –
        // unabhängig von der konfigurierten Startseite – auf die Selbstservice-Seite mit dem
        // Passwort-Formular geleitet. Gilt für den direkten wie für den TOTP-Zwei-Schritt-Pfad,
        // da beide unten dieselbe `page` verwenden.
        if (hasMustChangePassword(authentication)) {
            page = MUST_CHANGE_PASSWORD_PAGE;
        }
        String contextPath = (request.getContextPath() != null) ? request.getContextPath() : "";

        // === Zwei-Faktor-Gate ===
        // Wenn das Feature global aktiv ist UND dieser User TOTP aktiviert hat, wird die volle
        // Authentication NICHT finalisiert. Der AbstractAuthenticationProcessingFilter hat den
        // Context zwar bereits gespeichert – wir ueberschreiben ihn hier bewusst mit einem LEEREN
        // Context und legen die echte Authentication nur als "pending" in die Session. Erst der
        // TotpVerificationController setzt sie nach gueltigem Code wieder in den SecurityContext.
        // -> Wer nur das Passwort hat, kommt ohne zweiten Faktor NICHT durch (kein Bypass).
        if (totpAuthenticationService.isTotpRequired(userEmail)) {
            String targetUrl = contextPath + "/" + page;
            TotpPendingAuthentication pending = new TotpPendingAuthentication(authentication, userEmail, targetUrl);

            // KRITISCH (Bypass-Schutz): Der AbstractAuthenticationProcessingFilter hat VOR diesem
            // Handler bereits rememberMeServices.loginSuccess() aufgerufen und – falls der User
            // "Angemeldet bleiben" gewaehlt hat – ein gueltiges Remember-Me-Cookie + Persistent-Token
            // gesetzt. Ohne Widerruf koennte der RememberMeAuthenticationFilter den User beim naechsten
            // Request voll authentifizieren und damit den zweiten Faktor UMGEHEN. Daher hier Cookie +
            // DB-Token wieder entfernen. (Remember-Me nach bestandenem TOTP ist bewusst nicht Teil
            // dieses PRs.)
            try {
                // Entfernt den DB-Persistent-Token (falls das Request-Cookie einen traegt) und
                // fuegt ueber cancelCookie ein Loesch-Cookie hinzu.
                rememberMeServices.logout(request, response, authentication);
            } catch (Exception e) {
                log.warn("TOTP-Gate: Remember-Me-Widerruf fehlgeschlagen: {}", e.getMessage());
            }
            // Zusaetzlich (deterministisch): ein Loesch-Cookie als LETZTES remember-me-Set-Cookie
            // ausgeben, damit ein evtl. von loginSuccess() gesetztes gueltiges Cookie im Browser
            // sicher ueberschrieben wird (Last-Set-Cookie-wins) – unabhaengig von der Header-
            // Reihenfolge des Filters.
            // NOSONAR (S2092): bewusst KEIN secure-Flag. Hinter dem Reverse-Proxy wuerde secure=true
            // das Loesch-Cookie ueber HTTP verwerfen, sodass das gueltige remember-me-Cookie stehen
            // bliebe (2FA-Bypass). Es ist zudem ein leeres Loesch-Cookie (Max-Age=0), kein Secret.
            jakarta.servlet.http.Cookie loesch = new jakarta.servlet.http.Cookie("remember-me", ""); // NOSONAR
            loesch.setMaxAge(0);
            loesch.setPath(contextPath.isEmpty() ? "/" : contextPath);
            loesch.setHttpOnly(true);
            response.addCookie(loesch);

            // Echte Authentication aus dem SecurityContext entfernen und leeren Context persistieren,
            // damit KEIN Folge-Request als authentifiziert gilt.
            SecurityContext empty = SecurityContextHolder.createEmptyContext();
            SecurityContextHolder.setContext(empty);
            securityContextRepository.saveContext(empty, request, response);

            request.getSession(true).setAttribute(TotpPendingAuthentication.SESSION_ATTRIBUTE, pending);
            log.debug("TOTP required for user {} – deferring full authentication, redirect to {}", userEmail, TOTP_STEP_PATH);
            response.sendRedirect(contextPath + TOTP_STEP_PATH);
            return;
        }

        try {
            String baseUrl = extractBaseUrl(request);
            eventPublisher.publishEvent(new PlaintextLoginEvent(this, userEmail, userId, userEmail, mandat, baseUrl));
            log.debug("Published PlaintextLoginEvent for user: {} baseUrl: {}", userEmail, baseUrl);
        } catch (Exception e) {
            log.warn("Failed to publish login event: {}", e.getMessage());
        }

        // Remember-Me bei OAuth/OIDC: Der AbstractAuthenticationProcessingFilter ruft
        // rememberMeServices.loginSuccess(...) NUR beim Form-Login automatisch auf, beim
        // oauth2Login NICHT. Deshalb hier nachziehen – aber AUSSCHLIESSLICH für
        // OAuth2AuthenticationToken. Beim Form-Login (UsernamePasswordAuthenticationToken)
        // hat der Filter loginSuccess schon selbst aufgerufen; ein zweiter Aufruf würde einen
        // zweiten PERSISTENT_LOGINS-Eintrag (Doppel-Ausstellung) erzeugen.
        // Flag-gated: plaintext.security.remember-me-on-oauth (default true).
        if (securityProperties.isRememberMeOnOauth() && authentication instanceof OAuth2AuthenticationToken) {
            try {
                rememberMeServices.loginSuccess(request, response, authentication);
                log.debug("Issued remember-me cookie for OAuth login: {}", userEmail);
            } catch (Exception e) {
                log.warn("Failed to issue remember-me cookie for OAuth login {}: {}", userEmail, e.getMessage());
            }
        }

        // Absoluter Pfad (relativ würde gegen /login/oauth2/code/ aufgelöst).
        String redirectUrl = contextPath + "/" + page;
        log.debug("Redirecting user {} to {}", userEmail, redirectUrl);
        response.sendRedirect(redirectUrl);
    }

    /**
     * Vom {@code TotpVerificationController} nach gueltigem zweitem Faktor aufgerufen: publiziert
     * das (bislang zurueckgehaltene) Login-Event. Haelt die Event-Semantik identisch zum
     * Ein-Schritt-Login (Konsumenten erhalten den Login erst, wenn er vollstaendig ist).
     */
    public void publishLoginEvent(HttpServletRequest request, Authentication authentication) {
        try {
            String userEmail = authentication.getName();
            Long userId = extractUserId(authentication);
            String mandat = extractMandat(authentication);
            String baseUrl = extractBaseUrl(request);
            eventPublisher.publishEvent(new PlaintextLoginEvent(this, userEmail, userId, userEmail, mandat, baseUrl));
            log.debug("Published PlaintextLoginEvent (post-TOTP) for user: {}", userEmail);
        } catch (Exception e) {
            log.warn("Failed to publish post-TOTP login event: {}", e.getMessage());
        }
    }

    private Long extractUserId(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (a != null && a.startsWith("PROPERTY_MYUSERID_")) {
                try {
                    return Long.parseLong(a.substring("PROPERTY_MYUSERID_".length()));
                } catch (NumberFormatException e) { /* ignore */ }
            }
        }
        return -1L;
    }

    private String extractBaseUrl(HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) scheme = request.getScheme();
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null) host = request.getServerName();
        int port = request.getServerPort();
        String forwardedPort = request.getHeader("X-Forwarded-Port");
        if (forwardedPort != null) {
            try { port = Integer.parseInt(forwardedPort); } catch (NumberFormatException e) { /* ignore */ }
        }
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    private String extractMandat(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (a != null && a.startsWith("PROPERTY_MANDAT_")) {
                return a.substring("PROPERTY_MANDAT_".length()).toLowerCase();
            }
        }
        return "default";
    }

    /** Ob der User beim Login zwingend sein Passwort wechseln muss (Karte 306). */
    private static boolean hasMustChangePassword(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (MyUserDetailsService.MUST_CHANGE_PASSWORD_AUTHORITY.equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}

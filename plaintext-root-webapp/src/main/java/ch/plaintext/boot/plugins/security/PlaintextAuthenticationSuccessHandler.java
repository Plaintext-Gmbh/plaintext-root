/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.StartpageResolver;
import ch.plaintext.boot.deeplink.DeepLinkPendingStore;
import ch.plaintext.boot.plugins.log.Log;
import ch.plaintext.boot.plugins.security.service.MyUserDetailsService;
import ch.plaintext.boot.plugins.security.totp.TotpAuthenticationService;
import ch.plaintext.boot.plugins.security.totp.TotpPendingAuthentication;
import ch.plaintext.framework.EigeneAdresse;
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

    /** Target of the two-step login (TOTP code entry). */
    static final String TOTP_STEP_PATH = "/login/totp";

    /**
     * Self-service page with the password change form. Target on an enforced
     * password change (card 306), independently of a configured start page.
     */
    // NOSONAR (S2068): no password, but the file name of the self-service page. Sonar
    // trips merely because of the component "PASSWORD" in the constant name (card 458).
    static final String MUST_CHANGE_PASSWORD_PAGE = "myuser.xhtml"; // NOSONAR

    private final ApplicationEventPublisher eventPublisher;
    private final TotpAuthenticationService totpAuthenticationService;
    private final SecurityContextRepository securityContextRepository;
    private final PlaintextSecurityProperties securityProperties;
    /**
     * Used twice: {@code loginSuccess(...)} issues the remember-me cookie on OAuth/OIDC
     * (see #166 - the filter only does that automatically on the form login); as a {@code LogoutHandler}
     * {@code logout(...)} removes cookie + DB token again in the TOTP gate (bypass protection, see below).
     */
    private final PersistentTokenBasedRememberMeServices rememberMeServices;

    // @Lazy on rememberMeServices: the bean comes from PlaintextSecurityConfig, which in turn
    // needs this success handler in its constructor -> without @Lazy a bean cycle would arise.
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

        // Secured start page: the individual (valid) startpage or the fallback index.html -
        // nobody is locked out of the start page by an empty/invalid value.
        String page = StartpageResolver.resolve(authentication.getAuthorities());
        // Card 306: if this user has to change their password (e.g. the root initial password), they are -
        // independently of the configured start page - redirected to the self-service page with the
        // password form. Applies to the direct path as well as to the TOTP two-step path,
        // since both use the same `page` below.
        if (hasMustChangePassword(authentication)) {
            page = MUST_CHANGE_PASSWORD_PAGE;
        } else {
            // Card 345: if the user arrived through a deep link from a mail and was not logged in at
            // the time, the DeepLinkController remembered the target in the session. Instead of the
            // start page it now goes back there - but NOT as a URL passed through: from the
            // three remembered identifiers a /deeplink call is built again, which runs through the complete
            // chain of checks (tenant access, record access) anew. This is therefore
            // no open redirect point: the target can only ever be this one application,
            // and the login alone opens nothing that the user is not allowed to see anyway.
            // An enforced password change takes precedence (else branch).
            String deepLink = deepLinkZielAusSession(request);
            if (deepLink != null) {
                page = deepLink;
            }
        }
        String contextPath = (request.getContextPath() != null) ? request.getContextPath() : "";

        // === two-factor gate ===
        // If the feature is globally active AND this user has TOTP enabled, the full
        // authentication is NOT finalized. The AbstractAuthenticationProcessingFilter has already
        // saved the context - we deliberately overwrite it here with an EMPTY
        // context and put the real authentication into the session only as "pending". Only the
        // TotpVerificationController puts it back into the SecurityContext after a valid code.
        // -> whoever has only the password does NOT get through without a second factor (no bypass).
        if (totpAuthenticationService.isTotpRequired(userEmail)) {
            String targetUrl = contextPath + "/" + page;
            TotpPendingAuthentication pending = new TotpPendingAuthentication(authentication, userEmail, targetUrl);

            // CRITICAL (bypass protection): BEFORE this handler the AbstractAuthenticationProcessingFilter
            // has already called rememberMeServices.loginSuccess() and - if the user
            // chose "stay logged in" - set a valid remember-me cookie + persistent token.
            // Without revoking them the RememberMeAuthenticationFilter could fully authenticate the user on
            // the next request and thereby BYPASS the second factor. Hence remove cookie +
            // DB token again here. (Remember-me after a passed TOTP is deliberately not part
            // of this PR.)
            try {
                // Removes the DB persistent token (if the request cookie carries one) and
                // adds a deletion cookie via cancelCookie.
                rememberMeServices.logout(request, response, authentication);
            } catch (Exception e) {
                log.warn("TOTP-Gate: Remember-Me-Widerruf fehlgeschlagen: {}", e.getMessage());
            }
            // Additionally (deterministic): emit a deletion cookie as the LAST remember-me Set-Cookie,
            // so that a valid cookie possibly set by loginSuccess() is reliably overwritten in the
            // browser (last-set-cookie wins) - independently of the header
            // order of the filter.
            // NOSONAR (S2092): deliberately NO secure flag. Behind the reverse proxy secure=true would
            // make the deletion cookie be discarded over HTTP, so that the valid remember-me cookie would
            // stay in place (2FA bypass). Besides it is an empty deletion cookie (Max-Age=0), no secret.
            jakarta.servlet.http.Cookie loesch = new jakarta.servlet.http.Cookie("remember-me", ""); // NOSONAR
            loesch.setMaxAge(0);
            loesch.setPath(contextPath.isEmpty() ? "/" : contextPath);
            loesch.setHttpOnly(true);
            response.addCookie(loesch);

            // Remove the real authentication from the SecurityContext and persist an empty context,
            // so that NO subsequent request counts as authenticated.
            SecurityContext empty = SecurityContextHolder.createEmptyContext();
            SecurityContextHolder.setContext(empty);
            securityContextRepository.saveContext(empty, request, response);

            request.getSession(true).setAttribute(TotpPendingAuthentication.SESSION_ATTRIBUTE, pending);
            log.debug("TOTP required for user {} – deferring full authentication, redirect to {}", Log.mail(userEmail), TOTP_STEP_PATH);
            response.sendRedirect(contextPath + TOTP_STEP_PATH);
            return;
        }

        try {
            String baseUrl = basisUrl();
            eventPublisher.publishEvent(new PlaintextLoginEvent(this, userEmail, userId, userEmail, mandat, baseUrl));
            log.debug("Published PlaintextLoginEvent for user: {} baseUrl: {}", Log.mail(userEmail), baseUrl);
        } catch (Exception e) {
            log.warn("Failed to publish login event: {}", e.getMessage());
        }

        // Remember-me on OAuth/OIDC: the AbstractAuthenticationProcessingFilter calls
        // rememberMeServices.loginSuccess(...) automatically ONLY on the form login, on
        // oauth2Login NOT. Hence catch up here - but EXCLUSIVELY for
        // OAuth2AuthenticationToken. On the form login (UsernamePasswordAuthenticationToken)
        // the filter has already called loginSuccess itself; a second call would create a
        // second PERSISTENT_LOGINS entry (double issuance).
        // Flag-gated: plaintext.security.remember-me-on-oauth (default true).
        if (securityProperties.isRememberMeOnOauth() && authentication instanceof OAuth2AuthenticationToken) {
            try {
                rememberMeServices.loginSuccess(request, response, authentication);
                log.debug("Issued remember-me cookie for OAuth login: {}", Log.mail(userEmail));
            } catch (Exception e) {
                log.warn("Failed to issue remember-me cookie for OAuth login {}: {}", Log.mail(userEmail), e.getMessage());
            }
        }

        // Absolute path (a relative one would be resolved against /login/oauth2/code/).
        String redirectUrl = contextPath + "/" + page;
        log.debug("Redirecting user {} to {}", Log.mail(userEmail), redirectUrl);
        response.sendRedirect(redirectUrl);
    }

    /**
     * Called by the {@code TotpVerificationController} after a valid second factor: publishes
     * the (until then withheld) login event. Keeps the event semantics identical to the
     * one-step login (consumers receive the login only once it is complete).
     */
    public void publishLoginEvent(HttpServletRequest request, Authentication authentication) {
        try {
            String userEmail = authentication.getName();
            Long userId = extractUserId(authentication);
            String mandat = extractMandat(authentication);
            String baseUrl = basisUrl();
            eventPublisher.publishEvent(new PlaintextLoginEvent(this, userEmail, userId, userEmail, mandat, baseUrl));
            log.debug("Published PlaintextLoginEvent (post-TOTP) for user: {}", Log.mail(userEmail));
        } catch (Exception e) {
            log.warn("Failed to publish post-TOTP login event: {}", e.getMessage());
        }
    }

    /**
     * The remembered deep link (card 345) as a page designation WITHOUT a leading slash — the caller
     * prepends {@code contextPath + "/"}, exactly as for a normal page name. Returns
     * {@code null} if nothing was remembered.
     */
    static String deepLinkZielAusSession(HttpServletRequest request) {
        try {
            DeepLinkPendingStore.PendingDeepLink pending = DeepLinkPendingStore.entnehme(request);
            String pfad = DeepLinkPendingStore.alsPfad(pending);
            // alsPfad returns "/deeplink?..." — the leading slash has to go.
            return pfad == null ? null : pfad.substring(1);
        } catch (Exception e) {
            log.warn("Gemerkter Deep-Link konnte nicht aufgeloest werden: {}", e.getMessage());
            return null;
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

    /**
     * Optional on purpose: the handler is constructed by hand in tests and in
     * {@code LoginTestSupport}; there is no {@link EigeneAdresse} bean there, and the base URL
     * is then simply empty. In the running application Spring injects it.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EigeneAdresse eigeneAdresse;

    @org.springframework.beans.factory.annotation.Value("${plaintext.baseurl:}")
    private String plaintextBaseurl = "";

    /**
     * Base URL handed to {@link PlaintextLoginEvent} — from configuration only (Karte 1068).
     *
     * <p>Until 05.09.2026 this read {@code X-Forwarded-Proto/-Host/-Port} straight from the
     * request, so every consumer of the login event inherited an address the client could choose
     * (there was no consumer yet, but the next one — a login notification with a link — would
     * have been a phishing vector). Same source as every other outgoing link: {@link EigeneAdresse}
     * (setting {@code app.ownhost}, then {@code plaintext.app.ownhost}, then
     * {@code plaintext.baseurl}). The request is not consulted at all.
     */
    private String basisUrl() {
        String vorgabe = plaintextBaseurl == null ? "" : plaintextBaseurl;
        if (eigeneAdresse == null) {
            return EigeneAdresse.ohneEndSlash(vorgabe);
        }
        String basis = eigeneAdresse.basis(vorgabe);
        return basis == null ? "" : basis;
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

    /** Whether the user must change their password on login (card 306). */
    private static boolean hasMustChangePassword(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (MyUserDetailsService.MUST_CHANGE_PASSWORD_AUTHORITY.equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}

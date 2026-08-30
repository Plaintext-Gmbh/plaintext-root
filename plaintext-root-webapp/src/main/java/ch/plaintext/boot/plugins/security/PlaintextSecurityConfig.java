/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.plugins.security.ajax.JsfAjaxAwareAccessDeniedHandler;
import ch.plaintext.boot.plugins.security.ajax.JsfAjaxAwareAuthenticationEntryPoint;
import ch.plaintext.boot.plugins.security.ajax.JsfAjaxResponses;
import ch.plaintext.boot.plugins.security.magiclink.HashedOneTimeTokenService;
import ch.plaintext.boot.plugins.security.magiclink.MagicLinkGenerationSuccessHandler;
import ch.plaintext.boot.plugins.security.oidc.JdbcClientRegistrationRepository;
import ch.plaintext.boot.plugins.security.oidc.PlaintextOidcUserService;
import ch.plaintext.boot.plugins.security.service.MyRememberMeRepositoryRepository;
import ch.plaintext.boot.plugins.security.service.MyUserDetailsService;
import ch.plaintext.boot.deeplink.DeepLinkService;
import ch.plaintext.boot.security.PageAccessGuardFilter;
import ch.plaintext.boot.security.PageAccessGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.rememberme.*;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Configuration
@Slf4j
public class PlaintextSecurityConfig {

    /** Login page — form login, OIDC login and the Ajax redirects (card 385) point here. */
    private static final String LOGIN_PAGE = "/login.html";

    private final String rememberMeSigningKey;

    private final MyRememberMeRepositoryRepository tokenRepository;
    private final MyUserDetailsService userDetail;
    private final PlaintextAuthenticationSuccessHandler authenticationSuccessHandler;
    private final PlaintextSecurityProperties securityProperties;
    private final JdbcClientRegistrationRepository clientRegistrationRepository;
    private final PlaintextOidcUserService oidcUserService;
    private final HashedOneTimeTokenService hashedOneTimeTokenService;
    private final MagicLinkGenerationSuccessHandler magicLinkGenerationSuccessHandler;

    // Framework defaults: ignore CSRF.
    // ATTENTION: JSF pages (*.xhtml/*.html) are deliberately NOT ignored. The JSF view state is
    // state management and NO CSRF protection. Every <h:form> must therefore embed the token as
    // <input type="hidden" name="_csrf" value="#{_csrf.token}"/> (convention, see
    // CsrfFormInvariantTest). PrimeFaces also serializes the hidden field on AJAX submits.
    // SECURITY (card 314, item 4): /api/preferences/** is NO LONGER exempt from CSRF. The
    // endpoints are session-authenticated; without CSRF protection a foreign page could overwrite
    // the theme/colour settings of the logged-in user with a form POST.
    // The only callers are the four fetch() calls in includes/config.xhtml, and those
    // already append the token (params.append('_csrf', ...)) — the fix is therefore purely
    // configurational and breaks no productive flow.
    // Card 560: /token-login is out of here, because the endpoint no longer exists. A permitAll
    // on an unoccupied path is not merely superfluous -- it holds the door open in case a
    // controller under this mapping ever arises again.
    private static final List<String> DEFAULT_CSRF_IGNORE = List.of(
            "/nosec/**"
    );

    // Framework defaults: reachable without authentication
    private static final List<String> DEFAULT_PERMIT_ALL = List.of(
            "/login.xhtml", "/login.html", "/jakarta.faces.resource/**",
            "/actuator/health",
            "/nosec/**",
            // Card 635: JWK set of this instance (RFC 7517). The path is the one prescribed by RFC 8414 and
            // OpenID Connect Discovery -- every library looks there on its own,
            // which is why it lies outside /nosec and needs a permit of its own.
            //
            // Being reachable without a login IS the purpose: a peer shall be able to check a token
            // issued by us without somebody handing it the key by hand.
            // The endpoint only ever hands out PUBLIC keys
            // (JwksController; a test insists that no private part appears).
            "/.well-known/jwks.json",
            "/oauth2/**", "/login/oauth2/**",
            "/register", "/register/**",
            "/password-reset", "/password-reset/**",
            "/ott/generate",
            "/login/ott",
            // Second step of the TOTP login: at this point the user is NOT yet fully
            // authenticated (password ok, second factor pending). The page/POST
            // must therefore be reachable anonymously. CSRF stays active (token in the form),
            // the actual gate sits in the TotpVerificationController (pending session).
            "/login/totp", "/login-totp.xhtml",
            // Card 345: deep-link entry point. NOT open in the sense of unprotected — the
            // DeepLinkController checks by itself whether an authentication is present and sends
            // anonymous callers exclusively to the login page (the target is only remembered as
            // type/mandat/id in the server session, never passed through as a URL). Only this way
            // can the mail click of a logged-out user be continued after the login;
            // under anyRequest().authenticated() it would be lost. The actual checks
            // (tenant access, record access) are done by the DeepLinkResolver after the login.
            DeepLinkService.DEEPLINK_PATH
    );

    /**
     * SECURITY (card 308, item 4): pages that only ROOT may see — hard-wired,
     * independently of the menu visibility. One pattern per page; {@code *} matches within
     * a path segment and thereby covers {@code .html}, {@code .htm}, {@code .xhtml} and
     * {@code .jsf}. {@code /mandate*.*} covers {@code mandate}, {@code mandatemenu} and
     * {@code mandatemenudetail} — the last of which has no menu entry at all and was therefore
     * entirely unprotected.
     */
    private static final String[] ROOT_ONLY_PAGES = {
            // Card 345: overview of the deep-link targets. ROOT only — the page shows for which
            // modules/records links can be built, and can generate example links for arbitrary
            // tenants (the links themselves grant nothing, but the overview is
            // administrative information).
            "/deeplinks.*",
            "/mandate*.*",
            // Menu diagnostics: shows the visibility of EVERY menu entry incl. the reasons (which
            // role is missing, which tenant list applies). Administrative information — ROOT only.
            "/menudiagnose.*",
            // Guide to the menu control: refers to tenant lists and diagnostics, hence to
            // things that admin does not see.
            "/menuesteuerung-anleitung.*",
            "/rootentities.*",
            "/root-api-token.*",
            "/secrets.*",
            "/flyway.*",
            "/module.*",
            "/oidcconfig.*",
            "/performance.*",
            "/sessioninsights.*",
            "/settings.*",
            "/setup.*",
            "/webhooks.*"
    };

    /**
     * SECURITY (card 308, item 4): pages for ADMIN or ROOT — hard-wired.
     * {@code /useradmin.*} replaces the previous gate {@code myUserBackingBean.checkAccess()},
     * which hung in RENDER_RESPONSE.
     */
    private static final String[] ADMIN_PAGES = {
            "/useradmin.*",
            // Instruction by Daniel, 29.08.2026: mail texts are tenant-related and belong to the admin —
            // moved here from ROOT_ONLY_PAGES, menu entry now under "Admin".
            "/mailtemplates.*",
            "/adminentities.*",
            "/admin-api-token.*",
            "/i18n-translations.*",
            "/rollenzuteilung.*",
            "/modulrollen-anleitung.*",
            "/sessions.*",
            "/cron.*",
            "/debug.*",
            "/anforderungen.*",
            "/anforderungdetail.*",
            "/anforderungssettings.*",
            "/howtos.*",
            "/howtodetail.*",
            "/claudesummary.*"
    };

    public PlaintextSecurityConfig(MyRememberMeRepositoryRepository tokenRepository,
                                   MyUserDetailsService detail,
                                   PlaintextAuthenticationSuccessHandler authenticationSuccessHandler,
                                   PlaintextSecurityProperties securityProperties,
                                   JdbcClientRegistrationRepository clientRegistrationRepository,
                                   PlaintextOidcUserService oidcUserService,
                                   HashedOneTimeTokenService hashedOneTimeTokenService,
                                   MagicLinkGenerationSuccessHandler magicLinkGenerationSuccessHandler,
                                   org.springframework.core.env.Environment environment) {
        this.tokenRepository = tokenRepository;
        this.userDetail = detail;
        this.authenticationSuccessHandler = authenticationSuccessHandler;
        this.securityProperties = securityProperties;
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.oidcUserService = oidcUserService;
        this.hashedOneTimeTokenService = hashedOneTimeTokenService;
        this.magicLinkGenerationSuccessHandler = magicLinkGenerationSuccessHandler;
        this.rememberMeSigningKey = resolveRememberMeKey(securityProperties.getRememberMeKey(),
                isProduction(environment));
    }

    /**
     * SECURITY (card 314, item 13): recognizes the production environment by the active Spring profile
     * {@code prod} (that is how the Dockerfile sets it via {@code SPRING_PROFILES_ACTIVE}).
     */
    static boolean isProduction(org.springframework.core.env.Environment environment) {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * SECURITY (card 314, item 13): the signing key of the remember-me cookie is mandatory in PROD.
     * Until now a missing key only produced a WARN in the log and a volatile
     * random key — functionally inconspicuous (the cookies expired on every
     * restart), but exactly for that reason an accidentally missing key never
     * stands out in production. In dev/test the random key is retained, so that a local start still
     * works without an env setup.
     */
    private static String resolveRememberMeKey(String configured, boolean production) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (production) {
            throw new IllegalStateException(
                    "plaintext.security.remember-me-key (Env PLAINTEXT_SECURITY_REMEMBER_ME_KEY) ist in "
                            + "PROD Pflicht. Ohne stabilen Schluessel werden alle Remember-Me-Cookies bei "
                            + "jedem Neustart ungueltig.");
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String generated = Base64.getEncoder().withoutPadding().encodeToString(random);
        log.warn("plaintext.security.remember-me-key is not set; generated an ephemeral key. "
                + "All remember-me cookies will become invalid on restart. "
                + "Set plaintext.security.remember-me-key (or PLAINTEXT_SECURITY_REMEMBER_ME_KEY) "
                + "to a stable secret in production.");
        return generated;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository securityContextRepository,
                                                   PageAccessGuardService pageAccessGuardService) throws Exception {
        // CSRF ignore: framework defaults + app-specific paths
        List<String> csrfIgnore = new ArrayList<>(DEFAULT_CSRF_IGNORE);
        csrfIgnore.addAll(securityProperties.getCsrfIgnorePatterns());
        String[] csrfIgnoreArray = csrfIgnore.toArray(new String[0]);

        // PermitAll: framework defaults + app-specific paths
        List<String> permitAll = new ArrayList<>(DEFAULT_PERMIT_ALL);
        permitAll.addAll(securityProperties.getPermitAllPatterns());
        String[] permitAllArray = permitAll.toArray(new String[0]);

        http
                .securityContext(ctx -> ctx
                        .securityContextRepository(securityContextRepository)
                )
                .csrf(csrf -> {
                        // Plain (unmasked) CSRF token instead of the default XorCsrfTokenRequestAttributeHandler.
                        // The views embed the token as <input name="_csrf" value="#{_csrf.token}"/>
                        // (raw token). On submit, however, the default xor handler expects a BREACH-
                        // masked token -> the only CSRF-validated POST (/logout) failed with 403,
                        // the JS fallback then landed on GET /logout -> 404. With the attribute handler
                        // the raw token that is sent matches the validation.
                        csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
                        var matchers = new java.util.ArrayList<org.springframework.security.web.util.matcher.RequestMatcher>();
                        for (String pattern : csrfIgnore) {
                            matchers.add(PathPatternRequestMatcher.pathPattern(pattern));
                        }
                        // NOSONAR (S4502): the exception applies exclusively to token-based,
                        // sessionless paths — the framework default /nosec/**, plus
                        // whatever an app explicitly enters in plaintext.security.csrf-ignore-patterns.
                        // CSRF protects against session cookies being sent along
                        // automatically; where the authorization comes from a bearer token in the header
                        // there is nothing a foreign form could send along.
                        // All cookie-authenticated paths stay CSRF-protected (card 458).
                        csrf.ignoringRequestMatchers(matchers.toArray(new org.springframework.security.web.util.matcher.RequestMatcher[0])); // NOSONAR
                })
                .headers(headers -> {
                    headers.frameOptions(frame -> frame.sameOrigin());
                    headers.contentTypeOptions(org.springframework.security.config.Customizer.withDefaults());
                    headers.referrerPolicy(ref -> ref.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    headers.permissionsPolicy(pp -> pp.policy("camera=(), microphone=(), geolocation=(), payment=()"));
                    // SECURITY (card 314, item 2): HSTS explicitly instead of Spring's implicit default.
                    // Spring only writes the header on requests recognized as secure; behind the
                    // reverse proxy that depends on forward-headers-strategy=FRAMEWORK (set above).
                    // preload deliberately stays off: a preload entry is practically impossible to
                    // take back and would force ALL subdomains onto HTTPS permanently.
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .preload(false)
                            .maxAgeInSeconds(31536000L)); // 1 year
                    headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                            cspPolicy(securityProperties.getCsp().isScriptUnsafeInline())));
                })
                .authorizeHttpRequests(authorize -> {
                    authorize
                            // SECURITY/API CONTRACT (card 652): every error produced via response.sendError(...)
                            // — a 403 from the AccessDeniedHandler, a 404 from Spring MVC,
                            // a 500 from a controller — triggers a second, INTERNAL pass
                            // on /error in the servlet container (DispatcherType.ERROR), and does so only
                            // AFTER the complete filter chain has returned. The
                            // springSecurityFilterChain is mapped to REQUEST+ASYNC+ERROR by Boot default
                            // and therefore ran again — there the caller is anonymous (the
                            // bearer/token filters are FilterRegistrationBeans and only run on
                            // REQUEST), /error falls under anyRequest().authenticated(), and the
                            // LoginUrlAuthenticationEntryPoint overwrote the original
                            // status with a 302 to the login page.
                            //
                            // This was measurable without any authentication at all: GET /nosec/gibtsnicht — a
                            // permitAll path — returned a 302 to /login.html instead of a 404 (schuetu INT,
                            // 11.08.2026). For API clients the effect is more severe: a
                            // missing permission arrived as an HTML login page, and a script with
                            // `curl -L` saw HTTP 200 out of it — a denial of permission as a success.
                            //
                            // The dispatcher type ERROR is set exclusively by the container;
                            // a call from outside always carries REQUEST. This rule therefore does NOT open /error
                            // to external callers (counter-check in the ErrorDispatchChainTest).
                            .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                            .requestMatchers(permitAllArray).permitAll()
                            .requestMatchers("/actuator/**").hasRole("ADMIN")
                            // SECURITY (card 304): /api/i18n/** is translation administration and used to lie
                            // only under anyRequest().authenticated() -- every ROLE_USER was allowed to
                            // overwrite translations for ALL tenants via POST /api/i18n/import
                            // (I18nTranslation has no mandat column) and to pull all labels via
                            // GET /api/i18n/export. ROOT is listed as well, because
                            // the consuming page i18n-translations.xhtml hangs under the ROOT menu
                            // (RootSuperMenu roles={"ROOT"}) and would otherwise lose its export link
                            // if a root user has no admin role.
                            .requestMatchers("/api/i18n/**").hasAnyRole("ADMIN", "ROOT")
                            .requestMatchers("/api/branding/logo").authenticated()
                            .requestMatchers("/api/preferences/**").authenticated()
                            // SECURITY (card 314, item 5): the four /debug/* endpoints used to lie
                            // only under anyRequest().authenticated() — every logged-in USER saw
                            // absolute file system paths and JAR names (/debug/xhtml-resources), the
                            // complete page/role matrix (/debug/menu-scan) and the
                            // menu configuration of ALL tenants (/debug/mandate-menu-config).
                            // Attention: the existing ADMIN_PAGES pattern "/debug.*" only matches a
                            // view "debug.<ext>" and does NOT apply to these paths.
                            // In addition the controllers themselves are set to @Profile("dev"), so in
                            // PROD the endpoints do not exist at all any more (defense in depth).
                            .requestMatchers("/debug/**").hasRole("ROOT")
                            // SECURITY (card 308): defense in depth for the admin/ROOT pages of the
                            // framework. Until now the ONLY access protection of these pages was the
                            // menu visibility (PageAccessGuardService) — and that was fail-open:
                            // a menu link that did not end exactly in ".html" found no match
                            // and the guard permitted. With "mandatemenu.xhtml" (ROOT menu control
                            // for ALL tenants) that single letter was enough for every
                            // logged-in USER to operate the page. These rules apply
                            // independently of any menu logic.
                            // The patterns cover .html/.htm/.xhtml/.jsf ("*" matches within
                            // a path segment), because the FacesServlet is mapped to all four
                            // resp. the UrlRewriteFilter forwards .html/.htm to .xhtml.
                            // None of these view names exists in a consuming app
                            // (checked across plaintext-app/-guild/-schuetu/-iot/-fwtool), so the rules
                            // can lock nothing away there.
                            .requestMatchers(ROOT_ONLY_PAGES).hasRole("ROOT")
                            .requestMatchers(ADMIN_PAGES).hasAnyRole("ADMIN", "ROOT")
                            .anyRequest().authenticated();
                })
                // SECURITY (card 308, H3): the menu guard used to run only as an
                // f:event preRenderView (RENDER_RESPONSE, phase 6) — hence AFTER
                // INVOKE_APPLICATION (phase 5). A postback on a locked page had therefore already
                // executed the action method. Here it runs directly after the
                // authorization and thus before the FacesServlet.
                .addFilterAfter(new PageAccessGuardFilter(pageAccessGuardService), AuthorizationFilter.class)
                // BUGFIX (card 385): a JSF/PrimeFaces Ajax POST with an expired CSRF token
                // or an expired session used to get an HTTP 403 with a JSON body. The Ajax engine
                // strictly expects an XML partial response, cannot process JSON, reports
                // nothing — and the loading indicator spins forever ("the click does nothing"). This affects ALL
                // Ajax actions of the app, not only individual pages, and happens after every
                // blue/green deploy as well as after every re-login in another tab.
                // Instead the handlers deliver a valid partial response with <redirect>
                // (HTTP 200); PrimeFaces performs the redirect, the user lands on the
                // login. Non-Ajax requests keep Spring's default behaviour.
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new JsfAjaxAwareAuthenticationEntryPoint(
                                        new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE), LOGIN_PAGE),
                                JsfAjaxResponses::isJsfAjaxRequest)
                        .accessDeniedHandler(new JsfAjaxAwareAccessDeniedHandler(LOGIN_PAGE))
                )
                .formLogin(form -> form
                        .loginPage(LOGIN_PAGE)
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/index.html", true)
                        .failureUrl(LOGIN_PAGE + "?error=true")
                        .successHandler(authenticationSuccessHandler)
                        .permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage(LOGIN_PAGE)
                        .clientRegistrationRepository(clientRegistrationRepository)
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(oidcUserService)
                        )
                        .successHandler(authenticationSuccessHandler)
                        .failureUrl(LOGIN_PAGE + "?error=oidc")
                )
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern(org.springframework.http.HttpMethod.POST, "/logout"))
                        .logoutSuccessHandler(oidcLogoutSuccessHandler())
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "remember-me")
                        .clearAuthentication(true)
                        .permitAll()
                )
                .rememberMe(rememberMe -> rememberMe
                        .rememberMeServices(rememberMeServices())
                        .tokenRepository(tokenRepository)
                        // SECURITY (card 314, item 13): previously hard-coded 1209600s (2 weeks).
                        .tokenValiditySeconds((int) securityProperties.getRememberMeValidity().toSeconds())
                        .key(rememberMeSigningKey)
                )
                .oneTimeTokenLogin(ott -> ott
                        .tokenService(hashedOneTimeTokenService)
                        .tokenGenerationSuccessHandler(magicLinkGenerationSuccessHandler)
                        .loginProcessingUrl("/login/ott")
                        .tokenGeneratingUrl("/ott/generate")
                        .showDefaultSubmitPage(false)
                        .successHandler(authenticationSuccessHandler)
                );

        return http.build();
    }

    /**
     * Pass the logout on to the OIDC provider (RP-initiated logout, Keycloak {@code end_session_endpoint}):
     * without it the Keycloak SSO session survives a local logout, and a renewed "log in with SSO" click
     * then silently logs the user back in (without a password prompt). For form/magic-link logins
     * (no {@code OidcUser} principal) it automatically falls back to the local {@code postLogoutRedirectUri}.
     * ATTENTION: this requires that the "Valid post logout redirect URIs" of the respective Keycloak client
     * permit {@code {baseUrl}/login.html} -- otherwise Keycloak shows an error page there instead of the redirect
     * (the local logout itself is unaffected, only the way back).
     */
    @Bean
    public LogoutSuccessHandler oidcLogoutSuccessHandler() {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}/login.html");
        handler.setDefaultTargetUrl("/login.html");
        return handler;
    }

    /**
     * SECURITY (card 314, item 7): BCrypt cost factor {@value #BCRYPT_STRENGTH} instead of Spring's
     * default of 10. That default stems from 2010s hardware; 12 quadruples the effort
     * of an offline attack on a stolen hash and still costs only
     * a fraction of a second at login. BCrypt hashes carry their cost factor in the string
     * ({@code $2a$10$...}), so existing passwords stay valid without a migration —
     * they are merely raised to 12 on the next password change.
     */
    static final int BCRYPT_STRENGTH = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    RememberMeAuthenticationFilter rememberMeFilter(PersistentTokenBasedRememberMeServices service, AuthenticationManager auth) {
        return new RememberMeAuthenticationFilter(auth, service);
    }

    @Bean
    PersistentTokenBasedRememberMeServices rememberMeServices() {
        // Card 898: PlaintextRememberMeServices instead of the Spring class. There a series/token mismatch
        // no longer throws a CookieTheftException to the outside — it used to propagate into the
        // dispatcherServlet and turned /login.html into an HTTP 500 (six times in seven days,
        // measured in card 892). The theft protection is untouched: removeUserTokens runs
        // in processAutoLoginCookie BEFORE the throw, the cookie is deleted via cancelCookie.
        PersistentTokenBasedRememberMeServices services =
                new PlaintextRememberMeServices(rememberMeSigningKey, userDetail, tokenRepository);
        // On an OAuth/OIDC login there is no 'remember-me' form parameter. For
        // PlaintextAuthenticationSuccessHandler#loginSuccess to issue a persistent
        // cookie nonetheless, alwaysRemember has to be true (otherwise loginSuccess checks the
        // missing parameter and issues nothing). Side effect: the form login then
        // ALWAYS receives a remember-me cookie as well - intended for this personal-admin use case
        // (see plaintext.security.remember-me-on-oauth). Flag=false => the old
        // opt-in behaviour (only with the remember-me checkbox).
        services.setAlwaysRemember(securityProperties.isRememberMeOnOauth());
        return services;
    }

    /**
     * Assembles the content security policy.
     *
     * <p>Pulled out of the filter chain because {@code script-src} has been switchable since wave 4
     * ({@code plaintext.security.csp.script-unsafe-inline}) and this one difference would otherwise be
     * hidden in a 20-line string concatenation that no test reaches.
     *
     * <p><b>Why this is a switch at all.</b> With {@code 'unsafe-inline'} the browser executes
     * every {@code <script>} that stands in the document — an injected one included. The
     * rest of the policy is unaffected by that, but at this point it does not work against XSS.
     * Without {@code 'unsafe-inline'} only JavaScript from files of the same origin runs. The
     * switch stands at {@code true} (existing behaviour) and is thrown per app as soon as the
     * respective app has no inline JavaScript of its own left — checked by
     * {@code PlaintextInlineJsVertragTest} — and {@code joinfaces.primefaces.csp=true} is set,
     * so that PrimeFaces pulls its own handlers out.
     *
     * <p><b>ATTENTION, measured on 30.08.2026 against the running root application:</b> as soon as
     * {@code joinfaces.primefaces.csp=true} is set, PrimeFaces writes its OWN
     * {@code Content-Security-Policy} header on every Faces page and thereby REPLACES the one
     * built here — what is left is {@code script-src 'self' 'nonce-…';}, hence neither
     * {@code default-src} nor {@code frame-ancestors}, {@code form-action}, {@code img-src}
     * or {@code connect-src}. On non-Faces paths (REST, actuator, static files)
     * this header stays in force. Whoever throws the switch must therefore hand PrimeFaces the
     * complete policy ({@code joinfaces.primefaces.csp-policy}) — and there
     * {@code script-src} MUST be the LAST directive: PrimeFaces simply appends the nonce token
     * to the end of the string, otherwise it ends up in the wrong directive (measured:
     * {@code … form-action 'self' 'nonce-…'}).
     *
     * @param scriptUnsafeInline {@code true} = {@code script-src} carries {@code 'unsafe-inline'}
     * @return complete policy for the {@code Content-Security-Policy} header
     */
    static String cspPolicy(boolean scriptUnsafeInline) {
        String unsafeInline = scriptUnsafeInline ? "'unsafe-inline' " : "";
        return "default-src 'self'; " +
                // SECURITY (card 314, item 3): 'unsafe-eval' removed. Neither our own
                // JS code nor PrimeFaces 15 needs it (no eval()/new Function() in the repository).
                "script-src 'self' " + unsafeInline + "https://cdn.jsdelivr.net https://unpkg.com; " +
                // style-src keeps 'unsafe-inline': the views carry hundreds of style="…" attributes,
                // and PrimeFaces writes styles at runtime. That is a separate, considerably
                // larger rebuild — and an inline style is not the same risk as inline code.
                "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://unpkg.com; " +
                "img-src 'self' data: blob: https://*.tile.openstreetmap.org https://*.tile.opentopomap.org https://server.arcgisonline.com https://raw.githubusercontent.com https://wmts.geo.admin.ch https://unpkg.com; " +
                "font-src 'self' data:; " +
                "connect-src 'self' https://wmts.geo.admin.ch https://s3.amazonaws.com https://*.s3.amazonaws.com; " +
                "worker-src 'self' blob:; " +
                "frame-ancestors 'self'; " +
                "base-uri 'self'; " +
                // SECURITY (card 314, item 3): form-action reduced to 'self'.
                // In the whole repository there is no form with an absolute action URL
                // pointing to another domain; the OIDC flow leaves the application via a
                // 302 redirect (GET), not via a form POST, and is therefore not affected
                // by form-action.
                "form-action 'self'";
    }

}

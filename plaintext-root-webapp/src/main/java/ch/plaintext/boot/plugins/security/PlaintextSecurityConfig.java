/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.plugins.security.magiclink.HashedOneTimeTokenService;
import ch.plaintext.boot.plugins.security.magiclink.MagicLinkGenerationSuccessHandler;
import ch.plaintext.boot.plugins.security.oidc.JdbcClientRegistrationRepository;
import ch.plaintext.boot.plugins.security.oidc.PlaintextOidcUserService;
import ch.plaintext.boot.plugins.security.service.MyRememberMeRepositoryRepository;
import ch.plaintext.boot.plugins.security.service.MyUserDetailsService;
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

    private final String rememberMeSigningKey;

    private final MyRememberMeRepositoryRepository tokenRepository;
    private final MyUserDetailsService userDetail;
    private final PlaintextAuthenticationSuccessHandler authenticationSuccessHandler;
    private final PlaintextSecurityProperties securityProperties;
    private final JdbcClientRegistrationRepository clientRegistrationRepository;
    private final PlaintextOidcUserService oidcUserService;
    private final HashedOneTimeTokenService hashedOneTimeTokenService;
    private final MagicLinkGenerationSuccessHandler magicLinkGenerationSuccessHandler;

    // Framework-Defaults: CSRF ignorieren.
    // ACHTUNG: JSF-Seiten (*.xhtml/*.html) werden bewusst NICHT ignoriert. Der JSF-ViewState ist
    // State-Management und KEIN CSRF-Schutz. Jedes <h:form> muss deshalb das Token als
    // <input type="hidden" name="_csrf" value="#{_csrf.token}"/> einbetten (Konvention, siehe
    // CsrfFormInvariantTest). PrimeFaces serialisiert das Hidden-Feld auch bei AJAX-Submits mit.
    private static final List<String> DEFAULT_CSRF_IGNORE = List.of(
            "/autologin", "/token-login", "/nosec/**",
            "/api/preferences/**"
    );

    // Framework-Defaults: Ohne Authentication erreichbar
    private static final List<String> DEFAULT_PERMIT_ALL = List.of(
            "/autologin", "/autologin/**",
            "/token-login", "/token-login/**",
            "/login.xhtml", "/login.html", "/jakarta.faces.resource/**",
            "/actuator/health",
            "/nosec/**",
            "/oauth2/**", "/login/oauth2/**",
            "/register", "/register/**",
            "/password-reset", "/password-reset/**",
            "/ott/generate",
            "/login/ott",
            // Zweiter Schritt der TOTP-Anmeldung: der User ist hier noch NICHT voll
            // authentifiziert (Passwort ok, zweiter Faktor ausstehend). Die Seite/POST
            // muss daher anonym erreichbar sein. CSRF bleibt aktiv (Token im Formular),
            // der eigentliche Gate steckt im TotpVerificationController (pending-Session).
            "/login/totp", "/login-totp.xhtml"
    );

    /**
     * SECURITY (Karte 308, Punkt 4): Seiten, die nur ROOT sehen darf — hart verdrahtet,
     * unabhaengig von der Menue-Sichtbarkeit. Ein Pattern pro Seite; {@code *} matcht innerhalb
     * eines Pfad-Segments und deckt damit {@code .html}, {@code .htm}, {@code .xhtml} und
     * {@code .jsf} ab. {@code /mandate*.*} deckt {@code mandate}, {@code mandatemenu} und
     * {@code mandatemenudetail} ab — letztere hat gar keinen Menueeintrag und war damit voellig
     * ungeschuetzt.
     */
    private static final String[] ROOT_ONLY_PAGES = {
            "/mandate*.*",
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
            "/mailtemplates.*",
            "/webhooks.*"
    };

    /**
     * SECURITY (Karte 308, Punkt 4): Seiten fuer ADMIN oder ROOT — hart verdrahtet.
     * {@code /useradmin.*} ersetzt das bisherige, in RENDER_RESPONSE haengende Gate
     * {@code myUserBackingBean.checkAccess()}.
     */
    private static final String[] ADMIN_PAGES = {
            "/useradmin.*",
            "/adminentities.*",
            "/admin-api-token.*",
            "/i18n-translations.*",
            "/rollenzuteilung.*",
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
                                   MagicLinkGenerationSuccessHandler magicLinkGenerationSuccessHandler) {
        this.tokenRepository = tokenRepository;
        this.userDetail = detail;
        this.authenticationSuccessHandler = authenticationSuccessHandler;
        this.securityProperties = securityProperties;
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.oidcUserService = oidcUserService;
        this.hashedOneTimeTokenService = hashedOneTimeTokenService;
        this.magicLinkGenerationSuccessHandler = magicLinkGenerationSuccessHandler;
        this.rememberMeSigningKey = resolveRememberMeKey(securityProperties.getRememberMeKey());
    }

    private static String resolveRememberMeKey(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
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
        // CSRF-Ignore: Framework-Defaults + App-spezifische Pfade
        List<String> csrfIgnore = new ArrayList<>(DEFAULT_CSRF_IGNORE);
        csrfIgnore.addAll(securityProperties.getCsrfIgnorePatterns());
        String[] csrfIgnoreArray = csrfIgnore.toArray(new String[0]);

        // PermitAll: Framework-Defaults + App-spezifische Pfade
        List<String> permitAll = new ArrayList<>(DEFAULT_PERMIT_ALL);
        permitAll.addAll(securityProperties.getPermitAllPatterns());
        String[] permitAllArray = permitAll.toArray(new String[0]);

        http
                .securityContext(ctx -> ctx
                        .securityContextRepository(securityContextRepository)
                )
                .csrf(csrf -> {
                        // Plain (un-maskiertes) CSRF-Token statt des Default-XorCsrfTokenRequestAttributeHandler.
                        // Die Views betten das Token als <input name="_csrf" value="#{_csrf.token}"/> ein
                        // (raw Token). Der Default-Xor-Handler erwartet beim Submit jedoch ein BREACH-
                        // maskiertes Token -> der einzige CSRF-validierte POST (/logout) schlug mit 403 fehl,
                        // der JS-Fallback landete dann auf GET /logout -> 404. Mit dem Attribute-Handler
                        // passt das gesendete raw-Token zur Validierung.
                        csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
                        var matchers = new java.util.ArrayList<org.springframework.security.web.util.matcher.RequestMatcher>();
                        for (String pattern : csrfIgnore) {
                            matchers.add(PathPatternRequestMatcher.pathPattern(pattern));
                        }
                        csrf.ignoringRequestMatchers(matchers.toArray(new org.springframework.security.web.util.matcher.RequestMatcher[0]));
                })
                .headers(headers -> {
                    headers.frameOptions(frame -> frame.sameOrigin());
                    headers.contentTypeOptions(org.springframework.security.config.Customizer.withDefaults());
                    headers.referrerPolicy(ref -> ref.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    headers.permissionsPolicy(pp -> pp.policy("camera=(), microphone=(), geolocation=(), payment=()"));
                    headers.contentSecurityPolicy(csp -> csp
                            .policyDirectives("default-src 'self'; " +
                                    "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net https://unpkg.com; " +
                                    "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://unpkg.com; " +
                                    "img-src 'self' data: blob: https://*.tile.openstreetmap.org https://*.tile.opentopomap.org https://server.arcgisonline.com https://raw.githubusercontent.com https://wmts.geo.admin.ch https://unpkg.com; " +
                                    "font-src 'self' data:; " +
                                    "connect-src 'self' https://wmts.geo.admin.ch https://s3.amazonaws.com https://*.s3.amazonaws.com; " +
                                    "worker-src 'self' blob:; " +
                                    "frame-ancestors 'self'; " +
                                    "base-uri 'self'; " +
                                    "form-action 'self' https://*.plaintext.ch"));
                })
                .authorizeHttpRequests(authorize -> {
                    authorize
                            .requestMatchers(permitAllArray).permitAll()
                            .requestMatchers("/actuator/**").hasRole("ADMIN")
                            // SECURITY (Karte 304): /api/i18n/** ist Uebersetzungs-Verwaltung und lag
                            // vorher nur unter anyRequest().authenticated() -- jeder ROLE_USER durfte
                            // per POST /api/i18n/import Uebersetzungen fuer ALLE Mandanten
                            // ueberschreiben (I18nTranslation hat keine mandat-Spalte) und per
                            // GET /api/i18n/export alle Labels abziehen. ROOT ist mitgelistet, weil
                            // die konsumierende Seite i18n-translations.xhtml unter dem ROOT-Menue
                            // haengt (RootSuperMenu roles={"ROOT"}) und ihren Export-Link sonst
                            // verlieren wuerde, falls ein root-User keine admin-Rolle hat.
                            .requestMatchers("/api/i18n/**").hasAnyRole("ADMIN", "ROOT")
                            .requestMatchers("/api/branding/logo").authenticated()
                            .requestMatchers("/api/preferences/**").authenticated()
                            // SECURITY (Karte 308): Defense in Depth fuer die Admin-/ROOT-Seiten des
                            // Frameworks. Bisher war der EINZIGE Zugriffsschutz dieser Seiten die
                            // Menue-Sichtbarkeit (PageAccessGuardService) — und die war fail-open:
                            // ein Menue-Link, der nicht exakt auf ".html" endete, fand keinen Treffer
                            // und der Guard erlaubte. Bei "mandatemenu.xhtml" (ROOT-Menuesteuerung
                            // fuer ALLE Mandanten) reichte dieser eine Buchstabe, damit jeder
                            // eingeloggte USER die Seite bedienen konnte. Diese Regeln greifen
                            // unabhaengig von jeder Menue-Logik.
                            // Die Patterns decken .html/.htm/.xhtml/.jsf ab ("*" matcht innerhalb
                            // eines Pfad-Segments), weil das FacesServlet auf alle vier gemappt ist
                            // bzw. der UrlRewriteFilter .html/.htm auf .xhtml forwardet.
                            // Keine dieser View-Namen existiert in einer konsumierenden App
                            // (geprueft ueber plaintext-app/-guild/-schuetu/-iot/-fwtool), die Regeln
                            // koennen dort also nichts zusperren.
                            .requestMatchers(ROOT_ONLY_PAGES).hasRole("ROOT")
                            .requestMatchers(ADMIN_PAGES).hasAnyRole("ADMIN", "ROOT")
                            .anyRequest().authenticated();
                })
                // SECURITY (Karte 308, H3): Der Menue-Guard lief bisher nur als
                // f:event preRenderView (RENDER_RESPONSE, Phase 6) — also NACH
                // INVOKE_APPLICATION (Phase 5). Ein Postback auf eine gesperrte Seite hatte die
                // Action-Methode damit schon ausgefuehrt. Hier laeuft er direkt nach der
                // Autorisierung und damit vor dem FacesServlet.
                .addFilterAfter(new PageAccessGuardFilter(pageAccessGuardService), AuthorizationFilter.class)
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/index.html", true)
                        .failureUrl("/login.html?error=true")
                        .successHandler(authenticationSuccessHandler)
                        .permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login.html")
                        .clientRegistrationRepository(clientRegistrationRepository)
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(oidcUserService)
                        )
                        .successHandler(authenticationSuccessHandler)
                        .failureUrl("/login.html?error=oidc")
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
                        .tokenValiditySeconds(1209600) // 2 weeks
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
     * Logout gegen den OIDC-Provider durchreichen (RP-Initiated-Logout, Keycloak {@code end_session_endpoint}):
     * ohne das ueberlebt die Keycloak-SSO-Session einen lokalen Logout, ein erneuter "Mit SSO anmelden"-Klick
     * loggt den User dann still (ohne Passwort-Prompt) wieder ein. Faellt fuer Form-/Magic-Link-Logins
     * (kein {@code OidcUser}-Principal) automatisch auf den lokalen {@code postLogoutRedirectUri} zurueck.
     * ACHTUNG: setzt voraus, dass die "Valid post logout redirect URIs" des jeweiligen Keycloak-Clients
     * {@code {baseUrl}/login.html} erlauben -- sonst zeigt Keycloak dort eine Fehlerseite statt des Redirects
     * (der lokale Logout selbst ist davon nicht betroffen, nur die Rueckleitung).
     */
    @Bean
    public LogoutSuccessHandler oidcLogoutSuccessHandler() {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}/login.html");
        handler.setDefaultTargetUrl("/login.html");
        return handler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
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
        PersistentTokenBasedRememberMeServices services =
                new PersistentTokenBasedRememberMeServices(rememberMeSigningKey, userDetail, tokenRepository);
        // Bei OAuth/OIDC-Login gibt es keinen 'remember-me'-Formparameter. Damit
        // PlaintextAuthenticationSuccessHandler#loginSuccess dennoch einen persistenten
        // Cookie ausstellt, muss alwaysRemember=true sein (sonst prüft loginSuccess den
        // fehlenden Parameter und stellt nichts aus). Nebenwirkung: auch der Form-Login
        // erhält dann IMMER einen remember-me-Cookie – für diesen Personal-Admin-Use-Case
        // gewollt (siehe plaintext.security.remember-me-on-oauth). Flag=false => altes
        // Opt-in-Verhalten (nur mit remember-me-Checkbox).
        services.setAlwaysRemember(securityProperties.isRememberMeOnOauth());
        return services;
    }
}

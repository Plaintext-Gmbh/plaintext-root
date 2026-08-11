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

    /** Anmeldeseite — Form-Login, OIDC-Login und die Ajax-Redirects (Karte 385) zeigen hierhin. */
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

    // Framework-Defaults: CSRF ignorieren.
    // ACHTUNG: JSF-Seiten (*.xhtml/*.html) werden bewusst NICHT ignoriert. Der JSF-ViewState ist
    // State-Management und KEIN CSRF-Schutz. Jedes <h:form> muss deshalb das Token als
    // <input type="hidden" name="_csrf" value="#{_csrf.token}"/> einbetten (Konvention, siehe
    // CsrfFormInvariantTest). PrimeFaces serialisiert das Hidden-Feld auch bei AJAX-Submits mit.
    // SECURITY (Karte 314, Punkt 4): /api/preferences/** ist NICHT mehr CSRF-befreit. Die
    // Endpunkte sind session-authentifiziert; ohne CSRF-Schutz konnte eine fremde Seite per
    // Formular-POST die Theme-/Farbeinstellungen des eingeloggten Benutzers ueberschreiben.
    // Die einzigen Aufrufer sind die vier fetch()-Aufrufe in includes/config.xhtml, und die
    // haengen das Token bereits an (params.append('_csrf', ...)) — der Fix ist deshalb rein
    // konfigurativ und bricht keinen produktiven Flow.
    // Karte 560: /token-login ist hier raus, weil es den Endpunkt nicht mehr gibt. Ein permitAll
    // auf einen unbesetzten Pfad ist nicht bloss ueberfluessig -- er haelt die Tuer offen, falls je
    // wieder ein Controller unter diesem Mapping entsteht.
    private static final List<String> DEFAULT_CSRF_IGNORE = List.of(
            "/nosec/**"
    );

    // Framework-Defaults: Ohne Authentication erreichbar
    private static final List<String> DEFAULT_PERMIT_ALL = List.of(
            "/login.xhtml", "/login.html", "/jakarta.faces.resource/**",
            "/actuator/health",
            "/nosec/**",
            // Karte 635: JWK Set dieser Instanz (RFC 7517). Der Pfad ist der von RFC 8414 und
            // OpenID Connect Discovery vorgesehene -- dort sucht jede Bibliothek von selbst,
            // deshalb liegt er ausserhalb von /nosec und braucht eine eigene Freigabe.
            //
            // Ohne Anmeldung erreichbar zu sein IST der Zweck: Eine Gegenstelle soll ein von uns
            // ausgestelltes Token pruefen koennen, ohne dass ihr jemand den Schluessel von Hand
            // ueberreicht. Der Endpunkt gibt ausschliesslich OEFFENTLICHE Schluessel heraus
            // (JwksController; ein Test besteht darauf, dass kein privater Anteil erscheint).
            "/.well-known/jwks.json",
            "/oauth2/**", "/login/oauth2/**",
            "/register", "/register/**",
            "/password-reset", "/password-reset/**",
            "/ott/generate",
            "/login/ott",
            // Zweiter Schritt der TOTP-Anmeldung: der User ist hier noch NICHT voll
            // authentifiziert (Passwort ok, zweiter Faktor ausstehend). Die Seite/POST
            // muss daher anonym erreichbar sein. CSRF bleibt aktiv (Token im Formular),
            // der eigentliche Gate steckt im TotpVerificationController (pending-Session).
            "/login/totp", "/login-totp.xhtml",
            // Karte 345: Deep-Link-Einstieg. NICHT offen im Sinne von ungeschuetzt — der
            // DeepLinkController prueft selbst, ob eine Authentication vorliegt, und schickt
            // anonyme Aufrufer ausschliesslich zur Login-Seite (das Ziel wird nur als
            // type/mandat/id in der Server-Session gemerkt, nie als URL durchgereicht). Nur so
            // laesst sich der Mail-Klick eines abgemeldeten Benutzers nach dem Login fortsetzen;
            // unter anyRequest().authenticated() waere er verloren. Die eigentlichen Pruefungen
            // (Mandat-Zugriff, Datensatz-Zugriff) macht der DeepLinkResolver nach dem Login.
            DeepLinkService.DEEPLINK_PATH
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
            // Karte 345: Uebersicht der Deep-Link-Ziele. Nur ROOT — die Seite zeigt, auf welche
            // Module/Datensaetze sich Links bauen lassen, und kann Beispiel-Links fuer beliebige
            // Mandate erzeugen (die Links selbst verleihen nichts, die Uebersicht ist aber
            // Verwaltungsinformation).
            "/deeplinks.*",
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
     * SECURITY (Karte 314, Punkt 13): erkennt die Produktivumgebung am aktiven Spring-Profil
     * {@code prod} (so setzt es das Dockerfile per {@code SPRING_PROFILES_ACTIVE}).
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
     * SECURITY (Karte 314, Punkt 13): der Signierschluessel des Remember-Me-Cookies ist in PROD
     * Pflicht. Bisher wurde bei fehlendem Schluessel nur eine WARN geloggt und ein fluechtiger
     * Zufallsschluessel erzeugt — funktional unauffaellig (die Cookies verfielen bei jedem
     * Neustart), aber genau deshalb faellt ein versehentlich fehlender Schluessel im Betrieb nie
     * auf. In dev/test bleibt der Zufallsschluessel erhalten, damit ein lokaler Start weiterhin
     * ohne Env-Setup funktioniert.
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
                        // NOSONAR (S4502): Die Ausnahme trifft ausschliesslich tokenbasierte,
                        // sessionlose Pfade — Framework-Default /nosec/**, dazu
                        // was eine App ausdruecklich in plaintext.security.csrf-ignore-patterns
                        // eintraegt. CSRF schuetzt gegen das automatische Mitsenden von
                        // Session-Cookies; wo die Berechtigung aus einem Bearer-Token im Header
                        // stammt, gibt es nichts, was ein fremdes Formular mitschicken koennte.
                        // Alle cookie-authentifizierten Pfade bleiben CSRF-geschuetzt (Karte 458).
                        csrf.ignoringRequestMatchers(matchers.toArray(new org.springframework.security.web.util.matcher.RequestMatcher[0])); // NOSONAR
                })
                .headers(headers -> {
                    headers.frameOptions(frame -> frame.sameOrigin());
                    headers.contentTypeOptions(org.springframework.security.config.Customizer.withDefaults());
                    headers.referrerPolicy(ref -> ref.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    headers.permissionsPolicy(pp -> pp.policy("camera=(), microphone=(), geolocation=(), payment=()"));
                    // SECURITY (Karte 314, Punkt 2): HSTS explizit statt implizitem Spring-Default.
                    // Spring schreibt den Header nur auf als sicher erkannten Requests; hinter dem
                    // Reverse-Proxy haengt das an forward-headers-strategy=FRAMEWORK (oben gesetzt).
                    // preload bleibt bewusst aus: ein Preload-Eintrag ist praktisch nicht mehr
                    // ruecknehmbar und wuerde ALLE Subdomains dauerhaft auf HTTPS zwingen.
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .preload(false)
                            .maxAgeInSeconds(31536000L)); // 1 Jahr
                    headers.contentSecurityPolicy(csp -> csp
                            .policyDirectives("default-src 'self'; " +
                                    // SECURITY (Karte 314, Punkt 3): 'unsafe-eval' entfernt. Weder eigener
                                    // JS-Code noch PrimeFaces 15 brauchen es (im Repo kein eval()/new Function()).
                                    // 'unsafe-inline' bleibt vorerst: JSF/PrimeFaces rendert Inline-Handler und
                                    // Inline-<script>-Bloecke; die Ablösung per Nonce ist ein eigener Umbau
                                    // (siehe PR-Beschreibung, bewusst zurueckgestellt).
                                    "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://unpkg.com; " +
                                    "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://unpkg.com; " +
                                    "img-src 'self' data: blob: https://*.tile.openstreetmap.org https://*.tile.opentopomap.org https://server.arcgisonline.com https://raw.githubusercontent.com https://wmts.geo.admin.ch https://unpkg.com; " +
                                    "font-src 'self' data:; " +
                                    "connect-src 'self' https://wmts.geo.admin.ch https://s3.amazonaws.com https://*.s3.amazonaws.com; " +
                                    "worker-src 'self' blob:; " +
                                    "frame-ancestors 'self'; " +
                                    "base-uri 'self'; " +
                                    // SECURITY (Karte 314, Punkt 3): form-action auf 'self' reduziert.
                                    // Im gesamten Repo existiert kein Formular mit absoluter action-URL
                                    // auf eine andere Domain; der OIDC-Flow verlaesst die Anwendung per
                                    // 302-Redirect (GET), nicht per Formular-POST, und ist von
                                    // form-action daher nicht betroffen.
                                    "form-action 'self'"));
                })
                .authorizeHttpRequests(authorize -> {
                    authorize
                            // SECURITY/API-VERTRAG (Karte 652): Jeder ueber response.sendError(...)
                            // erzeugte Fehler — 403 aus dem AccessDeniedHandler, 404 aus Spring MVC,
                            // 500 aus einem Controller — loest im Servlet-Container einen zweiten,
                            // INTERNEN Durchlauf auf /error aus (DispatcherType.ERROR), und zwar erst
                            // NACHDEM die komplette Filterkette zurueckgekehrt ist. Die
                            // springSecurityFilterChain ist per Boot-Default auf REQUEST+ASYNC+ERROR
                            // gemappt und lief deshalb erneut — dort ist der Aufrufer anonym (die
                            // Bearer-/Token-Filter sind FilterRegistrationBeans und laufen nur auf
                            // REQUEST), /error faellt unter anyRequest().authenticated(), und der
                            // LoginUrlAuthenticationEntryPoint ueberschrieb den urspruenglichen
                            // Status mit 302 auf die Anmeldeseite.
                            //
                            // Messbar war das ohne jede Authentisierung: GET /nosec/gibtsnicht — ein
                            // permitAll-Pfad — lieferte 302 auf /login.html statt 404 (schuetu INT,
                            // 11.08.2026). Fuer API-Clients ist der Effekt schwerwiegender: eine
                            // fehlende Berechtigung kam als HTML-Anmeldeseite an, und ein Skript mit
                            // `curl -L` sah daraus HTTP 200 — eine Rechteverweigerung als Erfolg.
                            //
                            // Der DispatcherType ERROR wird ausschliesslich vom Container gesetzt;
                            // ein Aufruf von aussen traegt immer REQUEST. Diese Regel oeffnet /error
                            // also NICHT fuer externe Aufrufer (Gegenprobe im ErrorDispatchChainTest).
                            .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
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
                            // SECURITY (Karte 314, Punkt 5): die vier /debug/*-Endpoints lagen bisher
                            // nur unter anyRequest().authenticated() — jeder eingeloggte USER sah
                            // absolute Dateisystempfade und JAR-Namen (/debug/xhtml-resources), die
                            // vollstaendige Seiten-/Rollenmatrix (/debug/menu-scan) und die
                            // Menuekonfiguration ALLER Mandanten (/debug/mandate-menu-config).
                            // Achtung: das bestehende ADMIN_PAGES-Pattern "/debug.*" trifft nur eine
                            // View "debug.<ext>" und greift fuer diese Pfade NICHT.
                            // Zusaetzlich sind die Controller selbst auf @Profile("dev") gesetzt, in
                            // PROD existieren die Endpunkte also gar nicht mehr (Defense in Depth).
                            .requestMatchers("/debug/**").hasRole("ROOT")
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
                // BUGFIX (Karte 385): Ein JSF-/PrimeFaces-Ajax-POST mit abgelaufenem CSRF-Token
                // oder abgelaufener Session bekam bisher HTTP 403 mit JSON-Body. Die Ajax-Engine
                // erwartet zwingend eine XML-partial-response, kann JSON nicht verarbeiten, meldet
                // nichts — und der Ladeindikator dreht endlos ("Klick tut nichts"). Das trifft ALLE
                // Ajax-Aktionen der App, nicht nur einzelne Seiten, und passiert nach jedem
                // Blue/Green-Deploy sowie nach jedem Re-Login in einem anderen Tab.
                // Die Handler liefern statt dessen eine gueltige partial-response mit <redirect>
                // (HTTP 200); PrimeFaces fuehrt den Redirect aus, der Nutzer landet auf der
                // Anmeldung. Nicht-Ajax-Requests bleiben beim Spring-Default-Verhalten.
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
                        // SECURITY (Karte 314, Punkt 13): vorher hart 1209600s (2 Wochen).
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

    /**
     * SECURITY (Karte 314, Punkt 7): BCrypt-Kostenfaktor {@value #BCRYPT_STRENGTH} statt des
     * Spring-Defaults 10. Der Default stammt aus 2010er-Hardware; 12 vervierfacht den Aufwand
     * eines Offline-Angriffs auf einen erbeuteten Hash und kostet beim Login weiterhin nur
     * einen Bruchteil einer Sekunde. BCrypt-Hashes tragen ihren Kostenfaktor im String
     * ({@code $2a$10$...}), bestehende Passwoerter bleiben deshalb ohne Migration gueltig —
     * sie werden lediglich erst beim naechsten Passwortwechsel auf 12 angehoben.
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

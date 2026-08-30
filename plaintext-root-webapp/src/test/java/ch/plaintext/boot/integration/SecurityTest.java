/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying security configuration
 * allows/blocks expected URL patterns against a real PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityTest {


    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "securitytest");
    }

    @LocalServerPort
    private int port;

    @Test
    void versionEndpointIsPublic() {
        RestClient client = RestClient.create("http://localhost:" + port);
        String version = client.get().uri("/nosec/version").retrieve().body(String.class);
        assertNotNull(version);
    }

    @Test
    void healthEndpointIsPublic() {
        RestClient client = RestClient.create("http://localhost:" + port);
        String health = client.get().uri("/actuator/health").retrieve().body(String.class);
        assertNotNull(health);
        assertTrue(health.contains("status"));
    }

    @Test
    void applicationStartsSuccessfully() {
        // If we reach this point, the full application context with PostgreSQL
        // started without errors — security config, Flyway, JPA, etc.
        assertTrue(port > 0);
    }

    // --- CSRF validation on JSF pages (*.xhtml / *.html) ------------------------------------
    // The patterns /**/*.xhtml and /**/*.html were removed from DEFAULT_CSRF_IGNORE:
    // Spring Security now validates the _csrf token that every h:form embeds as a hidden input
    // (the JSF ViewState is state management, NOT CSRF protection).
    //
    // Observed framework behaviour on a missing/invalid token:
    //  - anonymous:      302 -> /login.html (AccessDenied is translated into a login
    //                    redirect for anonymous requests)
    //  - authenticated:  403
    //
    // IMPORTANT (CI robustness): as POST/rendering targets ONLY pages without a
    // module/DB data dependency are used deliberately — the CSRF filter applies to every .xhtml alike:
    //  - /login.xhtml         : permitAll, minimal, always renders 200 (token source + POST target)
    //  - /access-denied.xhtml : SYSTEM_PAGE (PageAccessGuardService), reachable when authenticated,
    //                           carries h:form id="fm" + _csrf + jakarta.faces.ViewState and does
    //                           NOT depend on the dashboard (dashboardBean.tiles). Only the AJAX postback
    //                           test needs a real ViewState and therefore uses this page.
    // NOT used is /index.xhtml: its dashboard tiles can throw a 500 while rendering in the integration
    // context (shared CI DB) — that is independent of CSRF (GET is unaffected).

    private static final String TEST_USER = "csrf-testuser";
    private static final String TEST_PASSWORD = "csrf-test-passwort";

    /** Robust POST target (permitAll, no dashboard) — the CSRF filter applies nonetheless. */
    private static final String POST_ZIEL = "/login.xhtml";
    /** Robust JSF page with h:form + ViewState for the AJAX postback (SYSTEM_PAGE, no dashboard). */
    private static final String AJAX_ZIEL = "/access-denied.xhtml";

    @Autowired
    private ch.plaintext.boot.plugins.security.persistence.MyUserRepository userRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Autowired
    private ch.plaintext.boot.plugins.security.totp.TotpService totpService;

    /** Client that does not throw on 4xx/5xx and does not follow redirects. */
    private RestClient lenientClient() {
        java.net.http.HttpClient jdkClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        return RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(jdkClient))
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {
                    // the status is checked in the test itself
                })
                .build();
    }

    @Test
    void anonymerPostAufXhtmlOhneCsrfTokenWirdGeblockt() {
        // /login.xhtml is permitAll — the blockage here is unambiguously CSRF, not authorization
        // EXPECTATION CHANGED (card 652): until 11.08.2026 this test demanded a
        // 3xx redirect to /login.html. Exactly that was the bug: `sendError(403)` triggered an
        // ERROR dispatch to /error, which ran through the security chain again and overwrote the
        // status with a redirect. The test had cemented that behaviour as the normal case —
        // a rejected request arrived at the caller as "go and log in",
        // and a script with `curl -L` read HTTP 200 out of it.
        // It is still blocked, only visibly so: 403 instead of a redirect.
        ResponseEntity<String> response = lenientClient().post()
                .uri("/login.xhtml")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("dummy=1")
                .retrieve()
                .toEntity(String.class);
        assertEquals(403, response.getStatusCode().value(),
                "POST auf .xhtml ohne _csrf-Token muss mit 403 geblockt werden, war: " + response.getStatusCode());
    }

    @Test
    void anonymerPostAufXhtmlMitGueltigemCsrfTokenWirdAkzeptiert() {
        LoginSeite seite = holeLoginSeite();
        ResponseEntity<String> response = lenientClient().post()
                .uri("/login.xhtml")
                .header(HttpHeaders.COOKIE, seite.session())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("_csrf=" + seite.csrfToken())
                .retrieve()
                .toEntity(String.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "POST auf /login.xhtml mit gültigem Token soll die Seite rendern, war: " + response.getStatusCode());
    }

    @Test
    void getAufXhtmlBleibtOhneTokenErlaubt() {
        // Safe methods (GET) are not covered by the CSRF validation
        ResponseEntity<String> response = lenientClient().get()
                .uri("/login.xhtml")
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void authentifizierterPostAufXhtmlOhneCsrfTokenGibt403() {
        AngemeldeteSession session = meldeTestUserAn();
        ResponseEntity<String> response = lenientClient().post()
                .uri(POST_ZIEL)
                .header(HttpHeaders.COOKIE, session.cookie())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("dummy=1")
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "Authentifizierter POST auf .xhtml ohne _csrf-Token muss 403 liefern");
    }

    @Test
    void authentifizierterPostAufXhtmlMitFalschemCsrfTokenGibt403() {
        AngemeldeteSession session = meldeTestUserAn();
        ResponseEntity<String> response = lenientClient().post()
                .uri(POST_ZIEL)
                .header(HttpHeaders.COOKIE, session.cookie())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("_csrf=falsches-token")
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "Authentifizierter POST auf .xhtml mit ungültigem _csrf-Token muss 403 liefern");
    }

    @Test
    void authentifizierterPostAufXhtmlMitGueltigemCsrfTokenWirdAkzeptiert() {
        AngemeldeteSession session = meldeTestUserAn();
        ResponseEntity<String> response = lenientClient().post()
                .uri(POST_ZIEL)
                .header(HttpHeaders.COOKIE, session.cookie())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("_csrf=" + session.csrfToken())
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "Authentifizierter POST auf .xhtml mit gültigem _csrf-Token muss durchgehen");
    }

    @Test
    void jsfAjaxPostbackMitCsrfTokenFunktioniert() {
        // PrimeFaces/JSF AJAX serializes all form fields including the _csrf hidden input.
        // Tested against a page with a real h:form + ViewState (access-denied.xhtml).
        AngemeldeteSession session = meldeTestUserAn();
        JsfSeite seite = holeJsfSeite(session.cookie(), AJAX_ZIEL);

        String body = "jakarta.faces.partial.ajax=true"
                + "&jakarta.faces.source=fm"
                + "&jakarta.faces.partial.execute=%40all"
                + "&fm=fm"
                + "&jakarta.faces.ViewState=" + java.net.URLEncoder.encode(seite.viewState(), java.nio.charset.StandardCharsets.UTF_8)
                + "&_csrf=" + seite.csrfToken();
        ResponseEntity<String> response = lenientClient().post()
                .uri(AJAX_ZIEL)
                .header(HttpHeaders.COOKIE, session.cookie())
                .header("Faces-Request", "partial/ajax")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "JSF-AJAX-Postback mit _csrf-Token muss durchgehen");
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("<partial-response"),
                "JSF-AJAX-Postback muss eine partial-response liefern");
    }

    /**
     * BUGFIX card 385: until then a JSF AJAX postback without a valid CSRF token returned
     * HTTP 403 with a JSON body. The PrimeFaces Ajax engine cannot parse that, reports nothing
     * and the loading indicator spins forever — reproduced on PROD as "the click does nothing".
     * Instead the request has to get a processable partial response with a &lt;redirect&gt;
     * (HTTP 200), so that PrimeFaces sends the user to the login.
     */
    @Test
    void jsfAjaxPostbackOhneCsrfTokenLiefertPartialResponseMitRedirect() {
        AngemeldeteSession session = meldeTestUserAn();
        JsfSeite seite = holeJsfSeite(session.cookie(), AJAX_ZIEL);

        String body = "jakarta.faces.partial.ajax=true"
                + "&jakarta.faces.source=fm"
                + "&fm=fm"
                + "&jakarta.faces.ViewState=" + java.net.URLEncoder.encode(seite.viewState(), java.nio.charset.StandardCharsets.UTF_8);
        ResponseEntity<String> response = lenientClient().post()
                .uri(AJAX_ZIEL)
                .header(HttpHeaders.COOKIE, session.cookie())
                .header("Faces-Request", "partial/ajax")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "Nur eine 200-Antwort wird von der Ajax-Engine ueberhaupt geparst");
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("<partial-response"),
                "Antwort muss XML-partial-response sein, war: " + response.getBody());
        assertTrue(response.getBody().contains("<redirect url=\"/login.html\"/>"),
                "Antwort muss den Redirect auf die Anmeldung enthalten, war: " + response.getBody());
        assertFalse(response.getBody().contains("\"error\""),
                "Kein JSON-Fehlerbody mehr");
    }

    /** Card 385: a non-Ajax POST keeps the unchanged Spring behaviour (403). */
    @Test
    void nichtAjaxPostbackOhneCsrfTokenGibtWeiterhin403() {
        AngemeldeteSession session = meldeTestUserAn();
        JsfSeite seite = holeJsfSeite(session.cookie(), AJAX_ZIEL);

        ResponseEntity<String> response = lenientClient().post()
                .uri(AJAX_ZIEL)
                .header(HttpHeaders.COOKIE, session.cookie())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("fm=fm&jakarta.faces.ViewState="
                        + java.net.URLEncoder.encode(seite.viewState(), java.nio.charset.StandardCharsets.UTF_8))
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "Ohne Faces-Request-Header bleibt es beim Spring-Default 403");
    }

    /**
     * Card 385: even without a session (expired / after a blue-green deploy) an Ajax request
     * must not get an unprocessable response — the AuthenticationEntryPoint likewise has to deliver a
     * partial response with a redirect instead of an HTML login redirect.
     */
    @Test
    void jsfAjaxRequestOhneSessionLiefertPartialResponseMitRedirect() {
        ResponseEntity<String> response = lenientClient().get()
                .uri("/access-denied.xhtml")
                .header("Faces-Request", "partial/ajax")
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("<redirect url=\"/login.html\"/>"),
                "war: " + response.getBody());
    }

    @Test
    void multipartPostAufXhtmlMitGueltigemCsrfTokenWirdAkzeptiert() {
        // p:fileUpload sends multipart/form-data to the view URL; the _csrf hidden field is
        // serialized along as a multipart field. Tomcat parses the fields because JoinFaces
        // registers the MultipartConfig on the FacesServlet — otherwise the CSRF validation
        // would not find the token and uploads would be blocked with a 403.
        AngemeldeteSession session = meldeTestUserAn();
        String boundary = "----csrfTestBoundary42";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"_csrf\"\r\n\r\n"
                + session.csrfToken() + "\r\n"
                + "--" + boundary + "--\r\n";
        ResponseEntity<String> response = lenientClient().post()
                .uri(POST_ZIEL)
                .header(HttpHeaders.COOKIE, session.cookie())
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "Multipart-POST auf .xhtml mit gültigem _csrf-Feld darf nicht geblockt werden (p:fileUpload)");
    }

    @Test
    void multipartPostAufXhtmlOhneCsrfTokenGibt403() {
        AngemeldeteSession session = meldeTestUserAn();
        String boundary = "----csrfTestBoundary42";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"egal\"\r\n\r\n"
                + "x\r\n"
                + "--" + boundary + "--\r\n";
        ResponseEntity<String> response = lenientClient().post()
                .uri(POST_ZIEL)
                .header(HttpHeaders.COOKIE, session.cookie())
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    /**
     * Proof of default-OFF: without {@code plaintext.security.totp.enabled=true} (the default in this
     * profile) nothing changes for ANYBODY - even a user with {@code totpEnabled=true}
     * is NOT sent to the second step but logged in directly. This way a PROD deploy with this
     * feature can break nothing as long as the flag is off.
     */
    @Test
    void beiFeatureFlagAusWirdKeinTotpSchrittErzwungen() {
        String username = "totp-flag-off-user";
        String passwort = "totp-flag-off-pw";
        if (userRepository.findByUsername(username) == null) {
            var user = new ch.plaintext.boot.plugins.security.model.MyUserEntity();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(passwort));
            user.setMandat("default");
            user.addRole("user");
            // The user HAS TOTP switched on - but the global feature is OFF by default.
            user.setTotpEnabled(true);
            user.setTotpSecret(totpService.generateSecret());
            userRepository.save(user);
        }

        LoginSeite loginSeite = holeLoginSeite();
        ResponseEntity<String> login = lenientClient().post()
                .uri("/login")
                .header(HttpHeaders.COOKIE, loginSeite.session())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=" + username + "&password=" + passwort + "&_csrf=" + loginSeite.csrfToken())
                .retrieve()
                .toEntity(String.class);
        assertTrue(login.getStatusCode().is3xxRedirection());
        String location = login.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location);
        assertFalse(location.contains("/login/totp"),
                "Bei ausgeschaltetem Feature darf KEIN TOTP-Schritt erzwungen werden, war: " + location);
        assertFalse(location.contains("error"), "Login muss normal durchlaufen, war: " + location);
    }

    // --- SECURITY card 304: /api/i18n/** only for ADMIN/ROOT --------------------------------
    // Previously /api/i18n/** fell under anyRequest().authenticated(): any ROLE_USER
    // could overwrite translations globally via POST /api/i18n/import (the entity I18nTranslation has
    // no mandat column), translations that are subsequently
    // rendered on admin pages -> stored XSS in the admin context. And they could pull all
    // labels via GET /api/i18n/export.
    //
    // The pairs (ordinary user -> 403 / admin -> 2xx) prove that the rejection comes from
    // AUTHORIZATION and not from CSRF: with a CSRF problem the
    // admin case would return 403 as well. The token is sent as an X-CSRF-TOKEN header — with
    // multipart/form-data that is more robust than a form field, because the CsrfFilter runs before
    // the multipart evaluation.

    private static final String PLAIN_USER = "i18n-plain-user";
    private static final String PLAIN_PASSWORD = "i18n-plain-passwort";

    private static final String IMPORT_CSV = "defaultLabel;languageCode;translatedText\n"
            + "SecurityTest-Label-304;en;SecurityTest-Value\n";

    @Test
    void i18nExportAlsNormalerUserGibt403() {
        AngemeldeteSession session = meldeAn(PLAIN_USER, PLAIN_PASSWORD, "user");
        ResponseEntity<String> response = lenientClient().get()
                .uri("/api/i18n/export")
                .header(HttpHeaders.COOKIE, session.cookie())
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER darf keine Uebersetzungen exportieren");
    }

    @Test
    void i18nExportAlsAdminFunktioniert() {
        AngemeldeteSession session = meldeTestUserAn();
        ResponseEntity<String> response = lenientClient().get()
                .uri("/api/i18n/export")
                .header(HttpHeaders.COOKIE, session.cookie())
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "ADMIN muss weiterhin exportieren duerfen (der Export-Link auf i18n-translations.xhtml)");
        assertNotNull(response.getBody());
        assertTrue(response.getBody().startsWith("defaultLabel;languageCode;translatedText"),
                "Export muss den CSV-Header liefern, war: " + response.getBody().substring(0, Math.min(80, response.getBody().length())));
    }

    @Test
    void i18nImportAlsNormalerUserGibt403() {
        AngemeldeteSession session = meldeAn(PLAIN_USER, PLAIN_PASSWORD, "user");
        ResponseEntity<String> response = importiere(session, IMPORT_CSV);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER darf keine Uebersetzungen importieren (Stored-XSS-Vektor)");
    }

    @Test
    void i18nImportAlsAdminFunktioniert() {
        AngemeldeteSession session = meldeTestUserAn();
        ResponseEntity<String> response = importiere(session, IMPORT_CSV);
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "ADMIN muss weiterhin importieren duerfen, war: " + response.getStatusCode()
                        + " / " + response.getBody());
    }

    @Test
    void i18nEndpunkteSindAnonymNichtErreichbar() {
        ResponseEntity<String> response = lenientClient().get()
                .uri("/api/i18n/export")
                .retrieve()
                .toEntity(String.class);
        assertTrue(response.getStatusCode().is3xxRedirection() || response.getStatusCode() == HttpStatus.FORBIDDEN,
                "Anonymer Export-Zugriff muss geblockt werden (Login-Redirect oder 403), war: " + response.getStatusCode());
    }

    // --- SECURITY card 308: page access protection fail-closed --------------------------------
    // Previously there was no test for this gap — and for a concrete reason: the guard
    // hung as an f:event preRenderView in the shared Facelets template, so it only took effect while
    // the page was RENDERED. In the SpringBootTest context, however, only views render that
    // reference the template with a LEADING slash (template="/includes/template.xhtml", e.g. access-denied);
    // all others (template="includes/template.xhtml", e.g. demo/useradmin) return 500. A
    // guard test was therefore not robustly possible.
    //
    // Since card 308 the guard runs as the PageAccessGuardFilter in the Spring Security chain, that is
    // BEFORE the FacesServlet. It answers without any rendering — and is thereby checkable here.
    // This module runs in mode=STRICT (application.yml).

    /** An ordinary USER (role "user" only) — deliberately NOT the admin test user. */
    private static final String GUARD_USER = "pageguard-plain-user";
    private static final String GUARD_PASSWORD = "pageguard-plain-passwort";

    @Test
    void h1_normalerUserKommtNichtAufDieRootMenuesteuerung() {
        AngemeldeteSession session = meldeAn(GUARD_USER, GUARD_PASSWORD, "user");
        ResponseEntity<String> response = lenientClient().get()
                .uri("/mandatemenu.html")
                .header(HttpHeaders.COOKIE, session.cookie())
                .retrieve()
                .toEntity(String.class);
        // Here the hard requestMatcher /mandate*.* (hasRole ROOT) from PlaintextSecurityConfig applies:
        // authenticated + not authorized -> 403.
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER darf die ROOT-Menuesteuerung aller Mandanten nicht aufrufen, war: "
                        + response.getStatusCode());
    }

    @Test
    void h2_detailseiteDerRootMenuesteuerungIstEbenfallsGesperrt() {
        AngemeldeteSession session = meldeAn(GUARD_USER, GUARD_PASSWORD, "user");
        ResponseEntity<String> response = lenientClient().get()
                .uri("/mandatemenudetail.html")
                .header(HttpHeaders.COOKIE, session.cookie())
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "mandatemenudetail.xhtml hatte gar keinen Menueeintrag und war voellig ungeschuetzt");
    }

    @Test
    void h2_auchAdminKommtNichtAnDieRootSeiten() {
        // The test user has admin+user, but not root. Proves that the rule checks ROLES and
        // not merely "logged in somehow".
        AngemeldeteSession session = meldeTestUserAn();
        ResponseEntity<String> response = lenientClient().get()
                .uri("/rootentities.html")
                .header(HttpHeaders.COOKIE, session.cookie())
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ADMIN ohne ROOT darf die ROOT-Datenverwaltung nicht aufrufen");
    }

    @Test
    void h2_viewOhneMenueeintragWirdImStrictModusVerweigert() {
        // /demo.html has no menu entry, no alias and no allowlist entry and is
        // also NOT hard-wired in the security config — so here exclusively the
        // fail-closed branch of the guard applies. Previously it returned "true" at this point.
        //
        // Card 523: the file demo.xhtml no longer exists since 04.08.2026 (a demo page with
        // Google Charts sample data that was shipped into EVERY app via plaintext-root-webapp
        // and was unprotected there). The test stays valid and even becomes
        // sharper because of it: the guard filter decides BEFORE the FacesServlet and thus independently
        // of whether there is a view behind the path at all — so it must not return 404
        // here, it has to reject.
        AngemeldeteSession session = meldeTestUserAn();
        ResponseEntity<String> response = lenientClient().get()
                .uri("/demo.html")
                .header(HttpHeaders.COOKIE, session.cookie())
                .retrieve()
                .toEntity(String.class);
        assertTrue(response.getStatusCode().is3xxRedirection(),
                "View ohne Zugriffsregel muss im Modus STRICT abgewiesen werden, war: " + response.getStatusCode());
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location);
        assertTrue(location.contains("/access-denied.html"),
                "Abweisung eines GET landet auf der Access-Denied-Seite, war: " + location);
    }

    @Test
    void h3_postbackAufGesperrteSeiteWirdVorDemFacesServletAbgewiesen() {
        // The core point of H3: the old guard ran in RENDER_RESPONSE (phase 6), action methods in
        // INVOKE_APPLICATION (phase 5) — so a postback to a locked page had already executed the
        // action. With a valid _csrf token it is ruled out that the 403 comes from
        // the CSRF validation: the same token returns a 200 on /login.xhtml (see
        // authentifizierterPostAufXhtmlMitGueltigemCsrfTokenWirdAkzeptiert).
        AngemeldeteSession session = meldeTestUserAn();
        ResponseEntity<String> response = lenientClient().post()
                .uri("/demo.html")
                .header(HttpHeaders.COOKIE, session.cookie())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("_csrf=" + session.csrfToken())
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "Ein POST auf eine gesperrte Seite darf das FacesServlet nie erreichen, war: "
                        + response.getStatusCode());
    }

    @Test
    void positiv_systemUndAllowlistSeitenBleibenErreichbar() {
        // Counter-check to fail-closed: the guard must not lock legitimate pages.
        // /access-denied.html is a system page, /myuser.html is on the framework allowlist
        // (linked in the topbar for EVERY user), /login-totp.html likewise (the second
        // login step, where the user is not yet fully authenticated).
        AngemeldeteSession session = meldeAn(GUARD_USER, GUARD_PASSWORD, "user");
        for (String pfad : new String[]{"/access-denied.html", "/myuser.html", "/login-totp.html"}) {
            ResponseEntity<String> response = lenientClient().get()
                    .uri(pfad)
                    .header(HttpHeaders.COOKIE, session.cookie())
                    .retrieve()
                    .toEntity(String.class);
            assertNotEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                    pfad + " darf vom Page-Guard nicht gesperrt werden");
            String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            assertTrue(location == null || !location.contains("/access-denied.html"),
                    pfad + " darf nicht auf access-denied umgeleitet werden, war: " + location);
        }
    }

    @Test
    void positiv_jsfRessourcenBleibenErreichbar() {
        // The filter applies to *.xhtml — JSF resources also end in .xhtml
        // (/jakarta.faces.resource/...). Without the exemption the whole PrimeFaces interface would be
        // without CSS/JS in STRICT mode.
        AngemeldeteSession session = meldeTestUserAn();
        ResponseEntity<String> response = lenientClient().get()
                .uri("/jakarta.faces.resource/primeicons/primeicons.css.xhtml?ln=primefaces")
                .header(HttpHeaders.COOKIE, session.cookie())
                .retrieve()
                .toEntity(String.class);
        assertNotEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "JSF-Ressourcen duerfen nicht vom Page-Guard gesperrt werden");
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertTrue(location == null || !location.contains("/access-denied.html"),
                "JSF-Ressourcen duerfen nicht auf access-denied umgeleitet werden, war: " + location);
    }

    // NOTE on the escaping part of card 304: a rendering regression test on
    // useradmin.xhtml ("payload in obj.startpage -> page contains &lt;script&gt;") is not
    // possible in THIS test context. The webapp root of the SpringBootTest is only
    // target/classes/META-INF/resources of this module; the shared Facelets template lies
    // in plaintext-root-template.jar and is therefore not resolvable:
    //   "useradmin.xhtml @5,89 <ui:composition template="includes/template.xhtml">
    //    Invalid path : includes/template.xhtml"  -> HTTP 500, independently of the escaping.
    // That is why only the template-free pages login.xhtml/access-denied.xhtml render here deliberately.
    // The escaping guardrail sits instead as a repository-wide source scan in
    // EscapeFalseInvariantTest (ch.plaintext.boot.web); the proof on the rendered HTML belongs
    // in the Playwright run against the deployed app.

    /** POST /api/i18n/import as a multipart upload with the CSRF token in the header. */
    private ResponseEntity<String> importiere(AngemeldeteSession session, String csv) {
        String boundary = "----i18nImportBoundary304";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"i18n.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + csv + "\r\n"
                + "--" + boundary + "--\r\n";
        return lenientClient().post()
                .uri("/api/i18n/import")
                .header(HttpHeaders.COOKIE, session.cookie())
                .header("X-CSRF-TOKEN", session.csrfToken())
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    private record LoginSeite(String session, String csrfToken) {
    }

    private record AngemeldeteSession(String cookie, String csrfToken) {
    }

    private record JsfSeite(String csrfToken, String viewState) {
    }

    /** Loads /login.xhtml and extracts the session cookie + the embedded _csrf token. */
    private LoginSeite holeLoginSeite() {
        ResponseEntity<String> get = lenientClient().get()
                .uri("/login.xhtml")
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, get.getStatusCode(), "Login-Seite muss ohne Auth abrufbar sein");

        String session = extrahiereSessionCookie(get, null);
        assertNotNull(session, "Login-Seite muss eine Session anlegen (CSRF-Token wird in der Session gespeichert)");

        String html = get.getBody();
        assertNotNull(html);
        Matcher matcher = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(), "Login-Seite muss das _csrf-Token als Hidden-Input einbetten");
        return new LoginSeite(session, matcher.group(1));
    }

    /**
     * Creates a test user (once) and logs them in via form login.
     * That also covers the acceptance criterion "login still works" —
     * the login POST itself runs through the CSRF validation.
     */
    private AngemeldeteSession meldeTestUserAn() {
        return meldeAn(TEST_USER, TEST_PASSWORD, "admin", "user");
    }

    /**
     * Creates (once) a user with the given roles and logs them in via form login.
     * Used by {@link #meldeTestUserAn()} (admin) and by the i18n authorization tests
     * (only {@code user}).
     */
    private AngemeldeteSession meldeAn(String username, String passwort, String... rollen) {
        if (userRepository.findByUsername(username) == null) {
            var user = new ch.plaintext.boot.plugins.security.model.MyUserEntity();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(passwort));
            user.setMandat("default");
            for (String rolle : rollen) {
                user.addRole(rolle);
            }
            userRepository.save(user);
        }

        LoginSeite loginSeite = holeLoginSeite();
        ResponseEntity<String> login = lenientClient().post()
                .uri("/login")
                .header(HttpHeaders.COOKIE, loginSeite.session())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=" + username + "&password=" + passwort + "&_csrf=" + loginSeite.csrfToken())
                .retrieve()
                .toEntity(String.class);
        assertTrue(login.getStatusCode().is3xxRedirection(), "Login muss redirecten, war: " + login.getStatusCode());
        String location = login.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location);
        assertFalse(location.contains("error"), "Login darf nicht fehlschlagen, Redirect war: " + location);

        // Session fixation protection rotates the session id on login
        String cookie = extrahiereSessionCookie(login, loginSeite.session());

        // After the login fetch the (rotated) CSRF token from login.xhtml — deliberately NOT from
        // index.xhtml (the dashboard can throw a 500 in the IT). The token is session-scoped and
        // is valid for POSTs to any .xhtml of the same session.
        ResponseEntity<String> tokenSeite = lenientClient().get()
                .uri("/login.xhtml")
                .header(HttpHeaders.COOKIE, cookie)
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, tokenSeite.getStatusCode(), "login.xhtml muss nach Login abrufbar sein");
        String html = tokenSeite.getBody();
        assertNotNull(html);
        Matcher matcher = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(), "login.xhtml muss das _csrf-Token einbetten");
        return new AngemeldeteSession(cookie, matcher.group(1));
    }

    /**
     * Loads a JSF page with the given session and extracts the _csrf token + ViewState.
     * For the AJAX postback test, which needs a real ViewState.
     */
    private JsfSeite holeJsfSeite(String cookie, String pfad) {
        ResponseEntity<String> get = lenientClient().get()
                .uri(pfad)
                .header(HttpHeaders.COOKIE, cookie)
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, get.getStatusCode(), pfad + " muss (authentifiziert) abrufbar sein");
        String html = get.getBody();
        assertNotNull(html);
        Matcher token = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(html);
        assertTrue(token.find(), pfad + " muss das _csrf-Token als Hidden-Input einbetten");
        Matcher viewState = Pattern.compile("name=\"jakarta\\.faces\\.ViewState\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertTrue(viewState.find(), pfad + " muss einen jakarta.faces.ViewState rendern");
        return new JsfSeite(token.group(1), viewState.group(1));
    }

    /** Extracts the JSESSIONID from Set-Cookie; falls back to the previous cookie. */
    private String extrahiereSessionCookie(ResponseEntity<String> response, String fallback) {
        java.util.List<String> setCookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        for (String setCookie : setCookies) {
            if (setCookie.startsWith("JSESSIONID=")) {
                return setCookie.split(";", 2)[0];
            }
        }
        return fallback;
    }
}

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

    // --- CSRF-Validierung auf JSF-Seiten (*.xhtml / *.html) ---------------------------------
    // Die Patterns /**/*.xhtml und /**/*.html wurden aus DEFAULT_CSRF_IGNORE entfernt:
    // Spring Security validiert jetzt das _csrf-Token, das alle h:form als Hidden-Input einbetten
    // (JSF-ViewState ist State-Management, KEIN CSRF-Schutz).
    //
    // Beobachtetes Framework-Verhalten bei fehlendem/ungültigem Token:
    //  - anonym:          302 -> /login.html (AccessDenied wird für anonyme Requests
    //                     in einen Login-Redirect übersetzt)
    //  - authentifiziert: 403
    //
    // WICHTIG (CI-Robustheit): Als POST-/Rendering-Ziele werden bewusst NUR Seiten ohne
    // Modul-/DB-Datenabhängigkeit verwendet — der CSRF-Filter greift für jede .xhtml gleich:
    //  - /login.xhtml         : permitAll, minimal, rendert immer 200 (Token-Quelle + POST-Ziel)
    //  - /access-denied.xhtml : SYSTEM_PAGE (PageAccessGuardService), authentifiziert erreichbar,
    //                           trägt h:form id="fm" + _csrf + jakarta.faces.ViewState und hängt
    //                           NICHT am Dashboard (dashboardBean.tiles). Nur der AJAX-Postback-Test
    //                           braucht einen echten ViewState und nutzt daher diese Seite.
    // NICHT verwendet wird /index.xhtml: dessen Dashboard-Tiles können im Integrationskontext
    // (geteilte CI-DB) beim Rendern 500 werfen — das ist unabhängig von CSRF (GET ist unberührt).

    private static final String TEST_USER = "csrf-testuser";
    private static final String TEST_PASSWORD = "csrf-test-passwort";

    /** Robustes POST-Ziel (permitAll, kein Dashboard) — der CSRF-Filter greift trotzdem. */
    private static final String POST_ZIEL = "/login.xhtml";
    /** Robuste JSF-Seite mit h:form + ViewState für den AJAX-Postback (SYSTEM_PAGE, kein Dashboard). */
    private static final String AJAX_ZIEL = "/access-denied.xhtml";

    @Autowired
    private ch.plaintext.boot.plugins.security.persistence.MyUserRepository userRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Autowired
    private ch.plaintext.boot.plugins.security.totp.TotpService totpService;

    /** Client, der bei 4xx/5xx nicht wirft und Redirects nicht folgt. */
    private RestClient lenientClient() {
        java.net.http.HttpClient jdkClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        return RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(jdkClient))
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {
                    // Status wird im Test selbst geprüft
                })
                .build();
    }

    @Test
    void anonymerPostAufXhtmlOhneCsrfTokenWirdGeblockt() {
        // /login.xhtml ist permitAll — die Blockade hier ist eindeutig CSRF, nicht Autorisierung
        ResponseEntity<String> response = lenientClient().post()
                .uri("/login.xhtml")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("dummy=1")
                .retrieve()
                .toEntity(String.class);
        assertTrue(response.getStatusCode().is3xxRedirection(),
                "POST auf .xhtml ohne _csrf-Token muss geblockt werden, war: " + response.getStatusCode());
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location);
        assertTrue(location.contains("/login.html"), "CSRF-Ablehnung anonymer Requests landet auf der Login-Seite, war: " + location);
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
        // Safe methods (GET) werden von der CSRF-Validierung nicht erfasst
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
        // PrimeFaces/JSF-AJAX serialisiert alle Formularfelder inkl. des _csrf-Hidden-Inputs.
        // Getestet gegen eine Seite mit echtem h:form + ViewState (access-denied.xhtml).
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
     * BUGFIX Karte 385: Bis dahin lieferte ein JSF-AJAX-Postback ohne gueltiges CSRF-Token
     * HTTP 403 mit JSON-Body. Die PrimeFaces-Ajax-Engine kann das nicht parsen, meldet nichts
     * und der Ladeindikator dreht endlos — auf PROD reproduziert als „Klick tut nichts".
     * Der Request muss statt dessen eine verarbeitbare partial-response mit &lt;redirect&gt;
     * bekommen (HTTP 200), damit PrimeFaces den Nutzer auf die Anmeldung schickt.
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

    /** Karte 385: Ein Nicht-Ajax-POST bleibt beim unveraenderten Spring-Verhalten (403). */
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
     * Karte 385: Auch ohne Session (abgelaufen / nach Blue-Green-Deploy) darf ein Ajax-Request
     * keine unverarbeitbare Antwort bekommen — der AuthenticationEntryPoint muss ebenfalls eine
     * partial-response mit Redirect liefern statt eines HTML-Login-Redirects.
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
        // p:fileUpload sendet multipart/form-data an die View-URL; das _csrf-Hidden wird als
        // Multipart-Feld mitserialisiert. Tomcat parst die Felder, weil JoinFaces die
        // MultipartConfig am FacesServlet registriert — sonst würde die CSRF-Validierung
        // das Token nicht finden und Uploads mit 403 blocken.
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
     * Default-OFF-Beweis: Ohne {@code plaintext.security.totp.enabled=true} (Default in diesem
     * Profil) aendert sich fuer NIEMANDEN etwas – selbst ein User mit {@code totpEnabled=true}
     * wird NICHT auf den zweiten Schritt geleitet, sondern direkt eingeloggt. So kann ein
     * PROD-Deploy mit diesem Feature nichts kaputt machen, solange das Flag aus ist.
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
            // User HAT TOTP aktiviert – aber das globale Feature ist im Default AUS.
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

    // --- SECURITY Karte 304: /api/i18n/** nur fuer ADMIN/ROOT -------------------------------
    // Vorher fiel /api/i18n/** unter anyRequest().authenticated(): ein beliebiger ROLE_USER
    // konnte per POST /api/i18n/import global (die Entity I18nTranslation hat keine
    // mandat-Spalte) Uebersetzungen ueberschreiben, die anschliessend auf Admin-Seiten
    // gerendert werden -> Stored XSS im Admin-Kontext. Und per GET /api/i18n/export alle
    // Labels abziehen.
    //
    // Die Paare (normaler User -> 403 / Admin -> 2xx) beweisen, dass die Ablehnung aus der
    // AUTORISIERUNG kommt und nicht aus CSRF: bei einem CSRF-Problem wuerde auch der
    // Admin-Fall 403 liefern. Das Token wird als X-CSRF-TOKEN-Header gesendet — bei
    // multipart/form-data ist das robuster als ein Formularfeld, weil der CsrfFilter vor der
    // Multipart-Auswertung laeuft.

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

    // --- SECURITY Karte 308: Seiten-Zugriffsschutz fail-closed --------------------------------
    // Vorher gab es fuer diese Luecke keinen Test — und zwar aus einem konkreten Grund: der Guard
    // hing als f:event preRenderView im gemeinsamen Facelets-Template, war also erst beim RENDERN
    // der Seite wirksam. Im SpringBootTest-Kontext rendern aber nur Views, die das Template mit
    // FUEHRENDEM Slash referenzieren (template="/includes/template.xhtml", z.B. access-denied);
    // alle anderen (template="includes/template.xhtml", z.B. demo/useradmin) liefern 500. Ein
    // Guard-Test war damit nicht robust moeglich.
    //
    // Seit Karte 308 laeuft der Guard als PageAccessGuardFilter in der Spring-Security-Kette, also
    // VOR dem FacesServlet. Er antwortet ohne jedes Rendering — und ist damit hier pruefbar.
    // Dieses Modul laeuft in mode=STRICT (application.yml).

    /** Ein normaler USER (nur Rolle "user") — bewusst NICHT der admin-Testuser. */
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
        // Greift der harte requestMatcher /mandate*.* (hasRole ROOT) aus PlaintextSecurityConfig:
        // authentifiziert + nicht autorisiert -> 403.
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
        // Der Testuser hat admin+user, aber nicht root. Beweist, dass die Regel ROLLEN prueft und
        // nicht bloss "irgendwie eingeloggt".
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
        // /demo.html hat keinen Menueeintrag, keinen Alias und keinen Allowlist-Eintrag und ist
        // auch NICHT hart in der Security-Config verdrahtet — hier greift also ausschliesslich der
        // fail-closed-Zweig des Guards. Vorher lieferte er an dieser Stelle "return true".
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
        // Kernpunkt von H3: der alte Guard lief in RENDER_RESPONSE (Phase 6), Action-Methoden in
        // INVOKE_APPLICATION (Phase 5) — ein Postback auf eine gesperrte Seite hatte die Action
        // also schon ausgefuehrt. Mit gueltigem _csrf-Token ist ausgeschlossen, dass die 403 aus
        // der CSRF-Validierung kommt: derselbe Token liefert auf /login.xhtml eine 200 (siehe
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
        // Gegenprobe zu fail-closed: der Guard darf legitime Seiten nicht sperren.
        // /access-denied.html ist Systemseite, /myuser.html steht auf der Framework-Allowlist
        // (in der Topbar fuer JEDEN User verlinkt), /login-totp.html ebenfalls (zweiter
        // Anmeldeschritt, dort ist der User noch nicht voll authentifiziert).
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
        // Der Filter greift auf *.xhtml — JSF-Ressourcen enden ebenfalls auf .xhtml
        // (/jakarta.faces.resource/...). Ohne die Ausnahme waere die gesamte PrimeFaces-Oberflaeche
        // im STRICT-Modus ohne CSS/JS.
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

    // ANMERKUNG zum Escaping-Teil von Karte 304: ein Rendering-Regressionstest auf
    // useradmin.xhtml ("Payload in obj.startpage -> Seite enthaelt &lt;script&gt;") ist in
    // DIESEM Testkontext nicht moeglich. Der Webapp-Root des SpringBootTest ist nur
    // target/classes/META-INF/resources dieses Moduls; das gemeinsame Facelets-Template liegt
    // in plaintext-root-template.jar und ist daher nicht auflösbar:
    //   "useradmin.xhtml @5,89 <ui:composition template="includes/template.xhtml">
    //    Invalid path : includes/template.xhtml"  -> HTTP 500, unabhaengig vom Escaping.
    // Deshalb rendern hier bewusst nur die template-freien Seiten login.xhtml/access-denied.xhtml.
    // Die Escaping-Leitplanke sitzt stattdessen als repo-weiter Quellcode-Scan in
    // EscapeFalseInvariantTest (ch.plaintext.boot.web); der Beweis am gerenderten HTML gehoert
    // in den Playwright-Lauf gegen die deployte App.

    /** POST /api/i18n/import als multipart-Upload mit CSRF-Token im Header. */
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

    /** Lädt /login.xhtml und extrahiert Session-Cookie + eingebettetes _csrf-Token. */
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
     * Legt (einmalig) einen Test-User an und meldet ihn per Form-Login an.
     * Deckt damit auch Akzeptanzkriterium "Login funktioniert weiterhin" ab —
     * der Login-POST selbst durchläuft die CSRF-Validierung.
     */
    private AngemeldeteSession meldeTestUserAn() {
        return meldeAn(TEST_USER, TEST_PASSWORD, "admin", "user");
    }

    /**
     * Legt (einmalig) einen Benutzer mit den angegebenen Rollen an und meldet ihn per Form-Login
     * an. Wird von {@link #meldeTestUserAn()} (admin) und von den i18n-Autorisierungstests
     * (nur {@code user}) genutzt.
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

        // Session-Fixation-Schutz rotiert die Session-ID beim Login
        String cookie = extrahiereSessionCookie(login, loginSeite.session());

        // Nach Login das (rotierte) CSRF-Token aus login.xhtml holen — bewusst NICHT aus
        // index.xhtml (Dashboard kann im IT 500 werfen). Das Token ist session-scoped und
        // gilt für POSTs auf jede beliebige .xhtml derselben Session.
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
     * Lädt eine JSF-Seite mit der angegebenen Session und extrahiert _csrf-Token + ViewState.
     * Für den AJAX-Postback-Test, der einen echten ViewState braucht.
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

    /** JSESSIONID aus Set-Cookie extrahieren; fällt auf den bisherigen Cookie zurück. */
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

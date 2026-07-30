/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.totp.TotpService;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-End-Integrationstest der Zwei-Schritt-TOTP-Anmeldung gegen echtes PostgreSQL.
 *
 * <p>Feature global AN (via {@link DynamicPropertySource}). Beweist die zentralen
 * Sicherheits-Invarianten:
 * <ul>
 *   <li>User mit {@code totpEnabled} landet nach Passwort-Login auf {@code /login/totp}
 *       (nicht direkt auf der Startseite).</li>
 *   <li><b>Kein Bypass:</b> mit ausstehendem zweitem Faktor ist eine geschuetzte Seite
 *       NICHT erreichbar.</li>
 *   <li>Mit gueltigem TOTP-Code wird der Login vollstaendig und die Startseite erreichbar.</li>
 *   <li>Ein Recovery-Code funktioniert genau einmal (one-time).</li>
 *   <li>Direkter Aufruf von {@code /login/totp} ohne pending-Zustand meldet niemanden an.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class TotpLoginIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("plaintext_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // TOTP-Feature fuer diesen Test global scharf schalten.
        registry.add("plaintext.security.totp.enabled", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MyUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TotpService totpService;

    private final DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final SystemTimeProvider timeProvider = new SystemTimeProvider();

    private static final String TOTP_USER = "totp-testuser";
    private static final String TOTP_PASSWORD = "totp-test-passwort";

    /**
     * Geschuetzte JSF-Seite als Bypass-Sonde. Bewusst {@code /access-denied.xhtml} (SYSTEM_PAGE,
     * absolute Template-Referenz, kein Dashboard) – identisch zur Wahl im SecurityTest: sie
     * rendert authentifiziert zuverlaessig 200 und ist ohne Auth nicht erreichbar (Redirect).
     * (Andere Seiten wie /myuser.xhtml nutzen eine relative Template-Referenz, die erst nach
     * dem prepare-package-Unpack aufloest und im reinen test-Lauf 500 werfen wuerde – das ist
     * ein Test-Umgebungs-Artefakt, kein Security-Verhalten.)
     */
    private static final String GESCHUETZTE_SEITE = "/access-denied.xhtml";

    private RestClient lenientClient() {
        java.net.http.HttpClient jdkClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        return RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(jdkClient))
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    // ---------------------------------------------------------------------------------------

    @Test
    void totpUser_wirdNachPasswortAufTotpSchrittGeleitet_undErstMitCodeRein() throws Exception {
        String secret = totpService.generateSecret();
        anlegenTotpUser(secret, null);

        // 1) Passwort-Login -> Redirect auf /login/totp (NICHT auf Startseite)
        Login login = passwortLogin();
        assertTrue(login.location.contains("/login/totp"),
                "Nach Passwort-Login mit aktivem TOTP muss auf /login/totp umgeleitet werden, war: " + login.location);

        // 2) BYPASS-CHECK: geschuetzte Seite ist mit ausstehendem 2. Faktor NICHT erreichbar
        ResponseEntity<String> geschuetzt = lenientClient().get()
                .uri(GESCHUETZTE_SEITE)
                .header(HttpHeaders.COOKIE, login.session)
                .retrieve().toEntity(String.class);
        assertTrue(geschuetzt.getStatusCode().is3xxRedirection(),
                "Ohne zweiten Faktor darf keine geschuetzte Seite erreichbar sein, war: " + geschuetzt.getStatusCode());
        String geschuetztLoc = geschuetzt.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(geschuetztLoc);
        assertTrue(geschuetztLoc.contains("/login"),
                "Zugriff ohne zweiten Faktor muss zur Anmeldung zuruecklenken, war: " + geschuetztLoc);

        // 3) Gueltigen TOTP-Code am zweiten Schritt einreichen -> voller Login
        String totpCsrf = holeCsrfVon("/login/totp", login.session);
        ResponseEntity<String> verify = lenientClient().post()
                .uri("/login/totp")
                .header(HttpHeaders.COOKIE, login.session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("code=" + aktuellerCode(secret) + "&_csrf=" + totpCsrf)
                .retrieve().toEntity(String.class);
        assertTrue(verify.getStatusCode().is3xxRedirection(), "TOTP-Verify muss redirecten, war: " + verify.getStatusCode());
        String verifyLoc = verify.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(verifyLoc);
        assertFalse(verifyLoc.contains("/login"), "Nach gueltigem Code darf NICHT zurueck zum Login, war: " + verifyLoc);

        // 4) Jetzt ist die geschuetzte Seite erreichbar (kein Redirect zum Login mehr)
        String sessionNachVerify = extrahiereSessionCookie(verify, login.session);
        ResponseEntity<String> jetztErlaubt = lenientClient().get()
                .uri(GESCHUETZTE_SEITE)
                .header(HttpHeaders.COOKIE, sessionNachVerify)
                .retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, jetztErlaubt.getStatusCode(),
                "Nach vollstaendigem 2FA-Login muss die geschuetzte Seite 200 liefern");
    }

    @Test
    void falscherTotpCode_haeltUserDraussen() {
        String secret = totpService.generateSecret();
        anlegenTotpUser(secret, null);

        Login login = passwortLogin();
        assertTrue(login.location.contains("/login/totp"));

        String totpCsrf = holeCsrfVon("/login/totp", login.session);
        ResponseEntity<String> verify = lenientClient().post()
                .uri("/login/totp")
                .header(HttpHeaders.COOKIE, login.session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("code=000000&_csrf=" + totpCsrf)
                .retrieve().toEntity(String.class);
        assertTrue(verify.getStatusCode().is3xxRedirection());
        String loc = verify.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(loc);
        assertTrue(loc.contains("/login/totp") && loc.contains("error"),
                "Falscher Code muss mit Fehler zum TOTP-Schritt zurueck, war: " + loc);

        // Weiterhin kein Zugriff auf geschuetzte Seiten
        ResponseEntity<String> geschuetzt = lenientClient().get()
                .uri(GESCHUETZTE_SEITE)
                .header(HttpHeaders.COOKIE, login.session)
                .retrieve().toEntity(String.class);
        assertTrue(geschuetzt.getStatusCode().is3xxRedirection(), "Nach falschem Code kein Zugriff");
    }

    @Test
    void recoveryCode_funktioniertGenauEinmal() {
        String secret = totpService.generateSecret();
        String recoveryPlain = "ABCD-EFGH-JKLM";
        Set<String> hashed = new HashSet<>();
        hashed.add(totpService.hashRecoveryCode(recoveryPlain));
        anlegenTotpUser(secret, hashed);

        // Erste Nutzung: Recovery-Code loggt ein
        Login login1 = passwortLogin();
        String csrf1 = holeCsrfVon("/login/totp", login1.session);
        ResponseEntity<String> verify1 = lenientClient().post()
                .uri("/login/totp")
                .header(HttpHeaders.COOKIE, login1.session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("code=" + recoveryPlain + "&_csrf=" + csrf1)
                .retrieve().toEntity(String.class);
        String loc1 = verify1.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(loc1);
        assertFalse(loc1.contains("/login"), "Gueltiger Recovery-Code muss einloggen, war: " + loc1);

        // Zweite Nutzung desselben Recovery-Codes: abgelehnt (one-time)
        Login login2 = passwortLogin();
        String csrf2 = holeCsrfVon("/login/totp", login2.session);
        ResponseEntity<String> verify2 = lenientClient().post()
                .uri("/login/totp")
                .header(HttpHeaders.COOKIE, login2.session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("code=" + recoveryPlain + "&_csrf=" + csrf2)
                .retrieve().toEntity(String.class);
        String loc2 = verify2.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(loc2);
        assertTrue(loc2.contains("/login/totp") && loc2.contains("error"),
                "Bereits verwendeter Recovery-Code muss abgelehnt werden, war: " + loc2);
    }

    @Test
    void kein2faBypassUeberRememberMe() {
        // Regression: Passwort-Login mit "Angemeldet bleiben" setzt VOR dem SuccessHandler ein
        // Remember-Me-Cookie. Das TOTP-Gate muss dieses Cookie widerrufen, sonst wuerde der
        // RememberMeAuthenticationFilter den User beim naechsten Request ohne zweiten Faktor
        // voll einloggen (Bypass).
        String secret = totpService.generateSecret();
        anlegenTotpUser(secret, null);

        // /login.xhtml -> Session + CSRF
        ResponseEntity<String> loginSeite = lenientClient().get()
                .uri("/login.xhtml").retrieve().toEntity(String.class);
        String session = extrahiereSessionCookie(loginSeite, null);
        String csrf = extrahiereCsrf(loginSeite.getBody());

        // Passwort-Login MIT remember-me=on
        ResponseEntity<String> login = lenientClient().post()
                .uri("/login")
                .header(HttpHeaders.COOKIE, session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=" + TOTP_USER + "&password=" + TOTP_PASSWORD + "&remember-me=on&_csrf=" + csrf)
                .retrieve().toEntity(String.class);
        String location = login.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location);
        assertTrue(location.contains("/login/totp"), "Muss auf TOTP-Schritt umleiten, war: " + location);

        // Das EFFEKTIVE remember-me-Cookie (letztes Set-Cookie gewinnt im Browser) darf KEIN
        // gueltiges Cookie sein – sonst 2FA-Bypass. Der Handler widerruft ein evtl. vom Filter
        // gesetztes Cookie mit einem Loesch-Cookie danach.
        String effektiv = effektivesRememberMeCookie(login);
        assertTrue(effektiv == null || istLoeschCookie(effektiv),
                "Das effektive remember-me-Cookie muss ein Loesch-Cookie sein (kein 2FA-Bypass), war: " + effektiv);

        // Selbst wenn ein Angreifer das (nicht vorhandene) Cookie mitschickt: kein Zugriff.
        // Wir schicken nur die Session (ohne gueltiges remember-me) -> geschuetzte Seite bleibt gesperrt.
        String sessionNachLogin = extrahiereSessionCookie(login, session);
        ResponseEntity<String> geschuetzt = lenientClient().get()
                .uri(GESCHUETZTE_SEITE)
                .header(HttpHeaders.COOKIE, sessionNachLogin)
                .retrieve().toEntity(String.class);
        assertTrue(geschuetzt.getStatusCode().is3xxRedirection(),
                "Trotz remember-me kein Zugriff ohne zweiten Faktor, war: " + geschuetzt.getStatusCode());
    }

    @Test
    void direkterAufrufVonLoginTotpOhnePendingLoggtNichtEin() {
        // Frische Session, kein Passwort-Login -> kein pending-Zustand.
        ResponseEntity<String> get = lenientClient().get()
                .uri("/login/totp")
                .retrieve().toEntity(String.class);
        // Ohne pending: Redirect zurueck zum Login (nie eine geschuetzte Seite).
        assertTrue(get.getStatusCode().is3xxRedirection(),
                "GET /login/totp ohne pending-Zustand muss zum Login umleiten, war: " + get.getStatusCode());
        String loc = get.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(loc);
        assertTrue(loc.contains("/login.html"), "War: " + loc);
    }

    // === Helpers ===

    private record Login(String session, String location) { }

    private void anlegenTotpUser(String secret, Set<String> hashedRecovery) {
        MyUserEntity user = userRepository.findByUsername(TOTP_USER);
        if (user == null) {
            user = new MyUserEntity();
            user.setUsername(TOTP_USER);
            user.setMandat("default");
            user.addRole("user");
        }
        user.setPassword(passwordEncoder.encode(TOTP_PASSWORD));
        user.setTotpSecret(secret);
        user.setTotpEnabled(true);
        user.setRecoveryCodes(hashedRecovery != null ? new HashSet<>(hashedRecovery) : new HashSet<>());
        userRepository.save(user);
    }

    private Login passwortLogin() {
        // /login.xhtml holen -> Session + CSRF
        ResponseEntity<String> loginSeite = lenientClient().get()
                .uri("/login.xhtml").retrieve().toEntity(String.class);
        String session = extrahiereSessionCookie(loginSeite, null);
        String csrf = extrahiereCsrf(loginSeite.getBody());

        ResponseEntity<String> login = lenientClient().post()
                .uri("/login")
                .header(HttpHeaders.COOKIE, session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=" + TOTP_USER + "&password=" + TOTP_PASSWORD + "&_csrf=" + csrf)
                .retrieve().toEntity(String.class);
        assertTrue(login.getStatusCode().is3xxRedirection(), "Passwort-Login muss redirecten, war: " + login.getStatusCode());
        String rotated = extrahiereSessionCookie(login, session);
        String location = login.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location);
        return new Login(rotated, location);
    }

    private String aktuellerCode(String secret) throws Exception {
        long counter = timeProvider.getTime() / 30;
        return codeGenerator.generate(secret, counter);
    }

    private String holeCsrfVon(String pfad, String session) {
        ResponseEntity<String> get = lenientClient().get()
                .uri(pfad)
                .header(HttpHeaders.COOKIE, session)
                .retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, get.getStatusCode(), pfad + " muss erreichbar sein, war: " + get.getStatusCode());
        return extrahiereCsrf(get.getBody());
    }

    private String extrahiereCsrf(String html) {
        assertNotNull(html);
        // login.xhtml / login-totp.xhtml betten das Token als value="..." ein.
        Matcher m = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        // Fallback: EL-gerenderte Variante value="TOKEN" nach name-Attribut in beliebiger Reihenfolge
        m = Pattern.compile("value=\"([^\"]+)\"\\s+name=\"_csrf\"").matcher(html);
        assertTrue(m.find(), "CSRF-Token nicht in HTML gefunden");
        return m.group(1);
    }

    private String extrahiereSessionCookie(ResponseEntity<String> response, String fallback) {
        for (String setCookie : response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
            if (setCookie.startsWith("JSESSIONID=")) {
                return setCookie.split(";", 2)[0];
            }
        }
        return fallback;
    }

    /**
     * Liefert das LETZTE remember-me-Set-Cookie (das im Browser gewinnt), oder {@code null}.
     */
    private String effektivesRememberMeCookie(ResponseEntity<String> response) {
        String last = null;
        for (String setCookie : response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
            if (setCookie.startsWith("remember-me=")) {
                last = setCookie;
            }
        }
        return last;
    }

    /** Ob ein Set-Cookie ein Loesch-Cookie ist (leerer Wert oder Max-Age=0 / 1970-Expiry). */
    private boolean istLoeschCookie(String setCookie) {
        String value = setCookie.substring(setCookie.indexOf('=') + 1).split(";", 2)[0];
        String lc = setCookie.toLowerCase();
        return value.isEmpty() || lc.contains("max-age=0") || lc.contains("expires=thu, 01 jan 1970");
    }
}

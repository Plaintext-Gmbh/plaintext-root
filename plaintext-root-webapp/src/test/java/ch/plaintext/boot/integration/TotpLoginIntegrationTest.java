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

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test of the two-step TOTP login against a real PostgreSQL.
 *
 * <p>Feature globally ON (via {@link DynamicPropertySource}). Proves the central
 * security invariants:
 * <ul>
 *   <li>A user with {@code totpEnabled} lands on {@code /login/totp} after the password login
 *       (not directly on the start page).</li>
 *   <li><b>No bypass:</b> with the second factor still pending a protected page
 *       is NOT reachable.</li>
 *   <li>With a valid TOTP code the login completes and the start page becomes reachable.</li>
 *   <li>A recovery code works exactly once (one-time).</li>
 *   <li>Calling {@code /login/totp} directly without a pending state logs nobody in.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TotpLoginIntegrationTest {


    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "totploginintegrationtest");
        // Arm the TOTP feature globally for this test.
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
     * A protected JSF page as a bypass probe. Deliberately {@code /access-denied.xhtml} (SYSTEM_PAGE,
     * absolute template reference, no dashboard) - identical to the choice in SecurityTest: it
     * renders 200 reliably when authenticated and is not reachable without auth (redirect).
     * (Other pages such as /myuser.xhtml use a relative template reference that only resolves
     * after the prepare-package unpack and would throw a 500 in a pure test run - that is
     * a test environment artifact, not security behaviour.)
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

        // 1) Password login -> redirect to /login/totp (NOT to the start page)
        Login login = passwortLogin();
        assertTrue(login.location.contains("/login/totp"),
                "Nach Passwort-Login mit aktivem TOTP muss auf /login/totp umgeleitet werden, war: " + login.location);

        // 2) BYPASS CHECK: with the second factor pending the protected page is NOT reachable
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

        // 3) Submit a valid TOTP code at the second step -> full login
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

        // 4) Now the protected page is reachable (no more redirect to the login)
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

        // Still no access to protected pages
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

        // First use: the recovery code logs in
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

        // Second use of the same recovery code: rejected (one-time)
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
        // Regression: a password login with "stay signed in" sets a remember-me cookie BEFORE the
        // SuccessHandler. The TOTP gate has to revoke that cookie, otherwise the
        // RememberMeAuthenticationFilter would log the user in fully on the next request without a
        // second factor (bypass).
        String secret = totpService.generateSecret();
        anlegenTotpUser(secret, null);

        // /login.xhtml -> session + CSRF
        ResponseEntity<String> loginSeite = lenientClient().get()
                .uri("/login.xhtml").retrieve().toEntity(String.class);
        String session = extrahiereSessionCookie(loginSeite, null);
        String csrf = extrahiereCsrf(loginSeite.getBody());

        // Password login WITH remember-me=on
        ResponseEntity<String> login = lenientClient().post()
                .uri("/login")
                .header(HttpHeaders.COOKIE, session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=" + TOTP_USER + "&password=" + TOTP_PASSWORD + "&remember-me=on&_csrf=" + csrf)
                .retrieve().toEntity(String.class);
        String location = login.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location);
        assertTrue(location.contains("/login/totp"), "Muss auf TOTP-Schritt umleiten, war: " + location);

        // The EFFECTIVE remember-me cookie (the last Set-Cookie wins in the browser) must NOT be a
        // valid cookie - otherwise 2FA bypass. The handler revokes a cookie possibly set by the
        // filter with a deletion cookie afterwards.
        String effektiv = effektivesRememberMeCookie(login);
        assertTrue(effektiv == null || istLoeschCookie(effektiv),
                "Das effektive remember-me-Cookie muss ein Loesch-Cookie sein (kein 2FA-Bypass), war: " + effektiv);

        // Even if an attacker sends the (non-existent) cookie along: no access.
        // We only send the session (without a valid remember-me) -> the protected page stays locked.
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
        // Fresh session, no password login -> no pending state.
        ResponseEntity<String> get = lenientClient().get()
                .uri("/login/totp")
                .retrieve().toEntity(String.class);
        // Without pending: redirect back to the login (never a protected page).
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
        // fetch /login.xhtml -> session + CSRF
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
        // login.xhtml / login-totp.xhtml embed the token as value="...".
        Matcher m = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        // Fallback: EL-rendered variant value="TOKEN" after the name attribute in any order
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
     * Returns the LAST remember-me Set-Cookie (the one that wins in the browser), or {@code null}.
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

    /** Whether a Set-Cookie is a deletion cookie (empty value or Max-Age=0 / 1970 expiry). */
    private boolean istLoeschCookie(String setCookie) {
        String value = setCookie.substring(setCookie.indexOf('=') + 1).split(";", 2)[0];
        String lc = setCookie.toLowerCase();
        return value.isEmpty() || lc.contains("max-age=0") || lc.contains("expires=thu, 01 jan 1970");
    }
}

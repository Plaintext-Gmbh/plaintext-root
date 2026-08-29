/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "plaintext.security")
@Data
public class PlaintextSecurityProperties {

    /**
     * Zusätzliche Pfade für die CSRF wird ignoriert (ergänzend zu Framework-Defaults).
     */
    private List<String> csrfIgnorePatterns = new ArrayList<>();

    /**
     * Zusätzliche Pfade die ohne Authentication erreichbar sind (ergänzend zu Framework-Defaults).
     */
    private List<String> permitAllPatterns = new ArrayList<>();

    /**
     * Signing key for the persistent remember-me cookie. Configure via
     * {@code plaintext.security.remember-me-key} (env: {@code PLAINTEXT_SECURITY_REMEMBER_ME_KEY}).
     *
     * <p>If left blank, a 32-byte random key is generated at startup. Tokens
     * issued under a generated key become invalid on the next restart, which
     * is fine for development but unsuitable for production. The framework
     * logs a WARN at startup whenever a generated key is in use.
     */
    private String rememberMeKey = "";

    /**
     * Schluesselmaterial fuer die Verschluesselung der TOTP-Secrets in {@code MY_USER_ENTITY}
     * (Zustandsbericht 29.08.2026, H3-Nebenbefund: das Secret lag im Klartext, die Recovery-Codes
     * daneben gehasht). Leer = es gilt {@link #rememberMeKey} (in PROD Pflicht, also ist die
     * Verschluesselung dort immer aktiv). Ein eigener Schluessel ist die bessere Wahl: dann
     * kann der Remember-Me-Schluessel rotiert werden, ohne dass jeder 2FA-Nutzer neu einrichten
     * muss. Rotation dieses Schluessels bedeutet Neu-Einrichtung fuer alle 2FA-Nutzer
     * (Recovery-Codes bleiben gueltig).
     */
    private String totpEncryptionKey = "";

    /**
     * Issue a persistent remember-me cookie on OAuth2/OIDC login (default {@code true}).
     *
     * <p>Spring's {@code AbstractAuthenticationProcessingFilter} calls
     * {@code rememberMeServices.loginSuccess(...)} automatically for form login,
     * but the {@code oauth2Login} flow does not. As a result an OAuth/OIDC login
     * never left a persistent cookie behind, so an iPhone home-screen shortcut to
     * a protected page forced the full OAuth &rarr; Keycloak &rarr; GitHub dance on
     * every visit.
     *
     * <p>When {@code true}, {@link PlaintextAuthenticationSuccessHandler} calls
     * {@code loginSuccess(...)} for OAuth authentications (only), and the
     * remember-me service runs with {@code alwaysRemember=true} so a cookie is
     * issued even though the OAuth callback carries no {@code remember-me} form
     * parameter. Because {@code alwaysRemember} is a property of the shared
     * remember-me service, form login then also always receives a remember-me
     * cookie — intentional for this single-user personal-admin use case.
     *
     * <p>When {@code false}, the legacy behaviour is restored: no
     * {@code loginSuccess} on OAuth login and {@code alwaysRemember=false} (form
     * login keeps its opt-in {@code remember-me} checkbox semantics).
     */
    private boolean rememberMeOnOauth = true;

    /**
     * SECURITY (Karte 314, Punkt 13): Gueltigkeitsdauer des persistenten Remember-Me-Cookies.
     * Vorher hart auf 14 Tage verdrahtet. Weil {@link #rememberMeOnOauth} in dieser Installation
     * {@code true} ist und damit JEDER Login — auch der Form-Login ohne Haekchen — einen
     * persistenten Cookie erhaelt, ist eine kuerzere Standardgueltigkeit die risikoaermere
     * Voreinstellung. Ueber {@code plaintext.security.remember-me-validity} anpassbar.
     */
    private java.time.Duration rememberMeValidity = java.time.Duration.ofDays(7);

    /**
     * SECURITY (Karte 314, Punkt 12): verlangt beim ERSTMALIGEN Verlinken eines bestehenden
     * lokalen Kontos mit einem OIDC-Subject den Claim {@code email_verified=true}. Fail-closed
     * (Default {@code true}); nur abschalten, wenn der eingesetzte IdP den Claim nachweislich
     * nicht liefert. Bereits verlinkte Konten und Auto-Create sind nicht betroffen.
     */
    private boolean oidcRequireVerifiedEmail = true;

    /**
     * TOTP / Zwei-Faktor-Authentifizierung (nur fuer lokale Passwort-User).
     * Additive Sub-Konfiguration; Default-OFF (siehe {@link TotpProperties}).
     */
    private TotpProperties totp = new TotpProperties();


    // Karte 560 (05.08.2026): plaintext.security.token-login.* ist ersatzlos entfallen, zusammen mit
    // dem Endpunkt /token-login selbst (TokenLoginController). Er war eine zweite Tuer neben der
    // markierten -- ein fuer Maschinen ausgestelltes Token ergab dort eine Browser-Session mit den
    // vollen DB-Rollen seines Besitzers. Karte 309 hat ihn abgesichert, Karte 544 den Scope-Zwang
    // auf SESSION verengt, und diese Karte baut ihn ab. Vorbedingung war eine Messung, keine
    // Annahme: in 30 Tagen kein einziger erfolgreicher Token-Login in PROD (Graylog, gegen die
    // Erfolgsmeldung des SessionLoginFinalizer geprueft, nicht gegen die Abwesenheit von Fehlern).
    //
    // Eine noch gesetzte Property dieses Praefixes ist ab hier wirkungslos. Sie loest keinen
    // Startfehler aus (Spring bindet unbekannte Schluessel ausserhalb dieses Objekts nicht) --
    // wer sie in einer app.env stehen hat, sollte sie beim naechsten Anfassen entfernen.


    /**
     * Konfiguration des optionalen zweiten Faktors (TOTP, Authenticator-App).
     *
     * <p><b>Sicherheits-Default:</b> {@code enabled=false}. Solange das Flag {@code false}
     * ist, aendert sich fuer niemanden etwas: kein zweiter Login-Schritt, kein Redirect,
     * keine Profil-Option, kein aktiver Verifikations-Gate. Nur bei {@code true} wird 2FA
     * ueberhaupt angeboten – und dann greift es ausschliesslich fuer User, die es selbst
     * aktiviert haben ({@code MyUserEntity.totpEnabled=true}). OIDC-only-User
     * ({@code passwordless}) sind grundsaetzlich nicht betroffen.
     */
    @Data
    public static class TotpProperties {

        /**
         * Master-Schalter fuer das gesamte TOTP-Feature. Default {@code false}
         * (PROD-sicher: ohne dieses Flag verhaelt sich das System exakt wie zuvor).
         */
        private boolean enabled = false;

        /**
         * Name, der in der Authenticator-App als Aussteller (Issuer) erscheint.
         * Teil der {@code otpauth://}-URI.
         */
        private String issuer = "Plaintext";

        /**
         * Toleranz in 30-Sekunden-Zeitfenstern bei der Code-Pruefung. {@code 1} erlaubt
         * je ein Fenster in Vergangenheit/Zukunft (RFC-6238-Empfehlung gegen Uhr-Drift).
         */
        private int allowedTimePeriodDiscrepancy = 1;

        /**
         * Anzahl der bei der Einrichtung generierten Einmal-Recovery-Codes.
         */
        private int recoveryCodeCount = 10;

        /**
         * PLATZHALTER fuer einen Folge-PR: Rollen, fuer die ein ADMIN/ROOT den zweiten
         * Faktor erzwingen koennte (z.B. {@code ADMIN}). Aktuell NICHT durchgesetzt –
         * Enforcement (User ohne eingerichtetes TOTP zur Einrichtung zwingen) ist bewusst
         * einem separaten PR vorbehalten. Dokumentiert in docs/security/TOTP_2FA.md.
         */
        private List<String> enforceForRoles = new ArrayList<>();
    }
}

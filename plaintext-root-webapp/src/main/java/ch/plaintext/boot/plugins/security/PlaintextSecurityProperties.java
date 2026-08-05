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


    /**
     * Session-Bootstrap aus einem ApiToken ({@code GET /token-login?token=}), Karte 309.
     * Siehe {@link TokenLoginProperties}.
     */
    private TokenLoginProperties tokenLogin = new TokenLoginProperties();

    /**
     * Konfiguration von {@code /token-login} ({@code ch.plaintext.boot.web.TokenLoginController}).
     *
     * <p><b>Hintergrund (Karte 309):</b> Der Endpunkt tauscht ein ApiToken gegen eine vollwertige
     * Browser-Session. Bis hierher liess er sich weder betrieblich abschalten, noch wertete er den
     * {@code scope}-Claim des Tokens aus: ein fuer Kiosk/Automation ausgestelltes {@code READ}-Token
     * lieferte an {@code /token-login} eine Browser-Session mit den <em>vollen</em> DB-Rollen des
     * Token-Besitzers. Beides ist hier konfigurierbar geworden.</p>
     */
    @Data
    public static class TokenLoginProperties {

        /**
         * Not-Aus fuer {@code /token-login}. Bei {@code false} antwortet der Endpunkt wie bei einem
         * ungueltigen Token (Redirect auf die Login-Seite).
         *
         * <p>Default bewusst {@code true}: der Endpunkt ist ein produktiv gedachter Anmeldeweg
         * (Ersatz fuer den entfernten {@code /autologin}), und ein stiller Default-Aus haette bestehende Links
         * ohne Vorwarnung gebrochen. Die eigentliche Absicherung leisten der erzwungene Scope
         * ({@link #getRequiredScopes()}) sowie Lockout-/2FA-Gate. Deployments ohne Token-Login-Nutzer
         * sollten das Flag dennoch auf {@code false} setzen.</p>
         */
        private boolean enabled = true;

        /**
         * Scopes, die ein Token tragen muss, um daraus eine Browser-Session bauen zu duerfen
         * (Claim-Wert case-insensitiv). Ein Token <b>ohne</b> {@code scope}-Claim wird immer
         * abgelehnt — fail-closed, analog zum {@code McpBearerTokenFilter} (Karte 312).
         *
         * <p>{@code SESSION} ist der dafuer vorgesehene, minimale Scope — und seit Karte 544 der
         * einzige. {@code READ} und {@code EINTRAGEN} reichen ohnehin nicht: sie werden fuer
         * maschinelle MCP-Zugriffe ausgestellt und sollen keine interaktive Session mit allen
         * DB-Rollen ergeben.</p>
         *
         * <p><b>Warum {@code ADMIN} hier nicht mehr steht (Karte 544, 05.08.2026):</b> {@code ADMIN}
         * war zugelassen, damit bestehende Vollzugriffs-Tokens weiterfunktionieren. Damit war jedes
         * ADMIN-MCP-Token zugleich ein Generalschluessel fuer eine Browser-Session mit den DB-Rollen
         * seines Besitzers — eine zweite Tuer neben der ausdruecklich markierten. Genau das war nicht
         * beabsichtigt: {@code ADMIN} wird als MCP-Berechtigungsgrad vergeben, nicht als
         * Session-Erlaubnis, und diese Tokens liegen im Klartext in MCP-Konfigurationen.
         *
         * <p>Der Bestand wurde vor der Umstellung erhoben: in 30 Tagen <b>kein einziger
         * erfolgreicher</b> {@code /token-login}; die drei theoretisch session-faehigen ADMIN-Tokens
         * sind Maschinen-Tokens mit {@code use_count 0}. Die Umstellung sperrt also niemanden aus,
         * der den Endpunkt tatsaechlich benutzt.</p>
         *
         * <p>Wer einzelne Instanzen bewusst anders will, setzt die Property weiterhin explizit —
         * geaendert hat sich nur die Voreinstellung.</p>
         */
        private List<String> requiredScopes = new ArrayList<>(List.of("SESSION"));
    }


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

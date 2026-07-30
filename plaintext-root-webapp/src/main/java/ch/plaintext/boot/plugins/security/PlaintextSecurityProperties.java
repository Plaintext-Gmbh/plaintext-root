/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.security.PageGuardMode;
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
     * TOTP / Zwei-Faktor-Authentifizierung (nur fuer lokale Passwort-User).
     * Additive Sub-Konfiguration; Default-OFF (siehe {@link TotpProperties}).
     */
    private TotpProperties totp = new TotpProperties();

    /**
     * Seiten-Zugriffsschutz auf Basis der Menue-Sichtbarkeit (Karte 308).
     * Siehe {@link PageGuardProperties}.
     */
    private PageGuardProperties pageGuard = new PageGuardProperties();

    /**
     * Konfiguration des Seiten-Zugriffsschutzes
     * ({@code ch.plaintext.boot.security.PageAccessGuardService} /
     * {@code PageAccessGuardFilter}).
     *
     * <p><b>Warum der Default {@link PageGuardMode#REPORT} ist:</b> das Framework wird von
     * mehreren Apps konsumiert, die eigene Views und eigene {@code @MenuAnnotation}s mitbringen.
     * Ein sofortiges {@link PageGuardMode#STRICT} wuerde dort jede View ohne Menueeintrag
     * aussperren. {@code REPORT} setzt alle uebrigen Verscharfungen (kanonischer Link-Vergleich,
     * {@code catch} -> verweigern, Allowlist, Aliase) durch, laesst aber Views ohne Zuordnung mit
     * einer WARN-Meldung passieren — so bekommt jede App erst ihre Lueckenliste ins Log und kann
     * dann gezielt auf {@code STRICT} umstellen. Die root-App selbst laeuft in {@code STRICT}
     * (gesetzt in ihrer {@code application.yml}).
     */
    @Data
    public static class PageGuardProperties {

        /**
         * Not-Aus. Bei {@code false} prueft weder Filter noch {@code preRenderView}-Guard
         * (Spring-Security-Regeln in {@code PlaintextSecurityConfig} bleiben davon unberuehrt).
         * Nur fuer den Fall gedacht, dass der Guard in PROD legitime Seiten sperrt und kein
         * Rollback moeglich ist.
         */
        private boolean enabled = true;

        /**
         * Verhalten bei Views ohne Menuezuordnung und Eltern-Rollen-Vererbung.
         * Siehe {@link PageGuardMode}.
         */
        private PageGuardMode mode = PageGuardMode.REPORT;

        /**
         * Zusaetzlich immer erreichbare Views (ergaenzend zu den Framework-Defaults in
         * {@code PageAccessGuardService}). Endung und fuehrender Slash sind egal
         * ({@code /myview.xhtml} == {@code myview.html} == {@code myview}). Ein Eintrag, der auf
         * {@code /**} endet, wirkt als Praefix ({@code nosec/**}).
         */
        private List<String> allowlist = new ArrayList<>();

        /**
         * View-Aliase: „bewache diese View wie diesen Menuelink". Schluessel ist die View, Wert
         * der Menue-Link, dessen Rollen/Mandanten-Sichtbarkeit gelten sollen. Fuer Detailseiten
         * ohne eigenen Menueeintrag, z.B.
         * {@code rechnungdetail.xhtml: rechnungen.html}. Beide Seiten werden kanonisiert, die
         * Endung ist also egal.
         */
        private Map<String, String> aliases = new LinkedHashMap<>();
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

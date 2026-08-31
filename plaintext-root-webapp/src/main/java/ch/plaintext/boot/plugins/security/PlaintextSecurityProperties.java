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
     * Additional paths for which CSRF is ignored (in addition to the framework defaults).
     */
    private List<String> csrfIgnorePatterns = new ArrayList<>();

    /**
     * Additional paths that are reachable without authentication (in addition to the framework defaults).
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
     * Key material for encrypting the TOTP secrets in {@code MY_USER_ENTITY}
     * (status report 29.08.2026, H3 side finding: the secret lay in clear text, the recovery codes
     * next to it hashed). Empty = {@link #rememberMeKey} applies (mandatory in PROD, so the
     * encryption is always active there). A dedicated key is the better choice: then
     * the remember-me key can be rotated without every 2FA user having to set up their
     * second factor again. Rotating this key means re-enrollment for all 2FA users
     * (recovery codes stay valid).
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
     * SECURITY (card 314, item 13): validity period of the persistent remember-me cookie.
     * Previously hard-wired to 14 days. Because {@link #rememberMeOnOauth} is {@code true} in this
     * installation and therefore EVERY login — including the form login without a checkmark — receives
     * a persistent cookie, a shorter default validity is the lower-risk
     * default. Adjustable via {@code plaintext.security.remember-me-validity}.
     */
    private java.time.Duration rememberMeValidity = java.time.Duration.ofDays(7);

    /**
     * SECURITY (card 314, item 12): requires the claim {@code email_verified=true} when an existing
     * local account is linked to an OIDC subject FOR THE FIRST TIME. Fail-closed
     * (default {@code true}); switch it off only if the IdP in use demonstrably does not deliver the
     * claim. Already linked accounts and auto-create are not affected.
     */
    private boolean oidcRequireVerifiedEmail = true;

    /**
     * TOTP / two-factor authentication (only for local password users).
     * Additive sub-configuration; default OFF (see {@link TotpProperties}).
     */
    private TotpProperties totp = new TotpProperties();

    /**
     * Content security policy. Additive sub-configuration; the default is the previous
     * behaviour (see {@link CspProperties}).
     */
    private CspProperties csp = new CspProperties();


    // Card 560 (05.08.2026): plaintext.security.token-login.* has been dropped without replacement, together
    // with the endpoint /token-login itself (TokenLoginController). It was a second door next to the
    // marked one -- a token issued for machines produced a browser session there with the
    // full DB roles of its owner. Card 309 secured it, card 544 narrowed the scope requirement
    // to SESSION, and this card dismantles it. The precondition was a measurement, not an
    // assumption: in 30 days not a single successful token login in PROD (Graylog, checked against the
    // success message of the SessionLoginFinalizer, not against the absence of errors).
    //
    // A property of this prefix that is still set has no effect from here on. It does not cause a
    // startup error (Spring does not bind unknown keys outside this object) --
    // whoever has it in an app.env should remove it the next time they touch that file.


    /**
     * Configuration of the optional second factor (TOTP, authenticator app).
     *
     * <p><b>Security default:</b> {@code enabled=false}. As long as the flag is {@code false},
     * nothing changes for anybody: no second login step, no redirect,
     * no profile option, no active verification gate. Only on {@code true} is 2FA
     * offered at all - and then it applies exclusively to users who have enabled it
     * themselves ({@code MyUserEntity.totpEnabled=true}). OIDC-only users
     * ({@code passwordless}) are fundamentally unaffected.
     */
    @Data
    public static class TotpProperties {

        /**
         * Master switch for the whole TOTP feature. Default {@code false}
         * (PROD-safe: without this flag the system behaves exactly as before).
         */
        private boolean enabled = false;

        /**
         * Name that appears as the issuer in the authenticator app.
         * Part of the {@code otpauth://} URI.
         */
        private String issuer = "Plaintext";

        /**
         * Tolerance in 30-second time windows when checking a code. {@code 1} permits
         * one window each into the past/future (RFC 6238 recommendation against clock drift).
         */
        private int allowedTimePeriodDiscrepancy = 1;

        /**
         * Number of one-time recovery codes generated during enrollment.
         */
        private int recoveryCodeCount = 10;

        /**
         * PLACEHOLDER for a follow-up PR: roles for which an ADMIN/ROOT could enforce the second
         * factor (e.g. {@code ADMIN}). Currently NOT enforced -
         * enforcement (forcing users without configured TOTP to enroll) is deliberately
         * reserved for a separate PR. Documented in docs/security/TOTP_2FA.md.
         */
        private List<String> enforceForRoles = new ArrayList<>();
    }

    /**
     * Control over individual directives of the content security policy (set in
     * {@link PlaintextSecurityConfig}).
     *
     * <p>There is exactly one switch here, and it is deliberately that small: everything else about the
     * policy is the same for all apps and has no reason to be configurable. What an app
     * may additionally load (map tiles, CDN) stands as a list in the policy itself.
     */
    @Data
    public static class CspProperties {

        /**
         * Does {@code script-src} still carry {@code 'unsafe-inline'}? Default {@code true} —
         * that is the behaviour from before wave 4, and it stays that way until an app explicitly
         * switches over.
         *
         * <p><b>What the switch means.</b> With {@code 'unsafe-inline'} the browser executes
         * every {@code <script>} that stands in the document — including one that an attacker
         * has written into it. At this point the CSP therefore does not protect; it only looks
         * as if it did. Set to {@code false}, only JavaScript from files of the same
         * origin runs, and an XSS hole without write access to files becomes ineffective.
         *
         * <p><b>When an app may switch over.</b> Only once two things hold:
         * <ol>
         *   <li>{@code joinfaces.primefaces.csp=true} is set — then PrimeFaces pulls its
         *       own handlers out of the markup and gives its blocks a nonce;</li>
         *   <li>there is no inline JavaScript left in the app's own markup. Exactly that is checked by
         *       {@code PlaintextInlineJsVertragTest} (plaintext-root-archtests): enforcing via
         *       {@code -Dplaintext.arch.inline-js=enforce} resp. the Surefire lines in the
         *       webapp pom. For root both have been fulfilled since wave 4.</li>
         * </ol>
         * The switch is thrown per app ({@code plaintext.security.csp.script-unsafe-inline=false}),
         * so that a failure affects one app and not the whole family.
         */
        private boolean scriptUnsafeInline = true;
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import ch.plaintext.boot.plugins.security.PlaintextSecurityProperties;
import ch.plaintext.boot.plugins.security.SessionLoginFinalizer;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Session-Bootstrap aus einem langlebigen ApiToken-JWT ({@code GET /token-login?token=<JWT>}).
 *
 * <p>Sauberer Ersatz fuer den statischen {@code /autologin?key=} (Task 013b, Option B): statt eines
 * unbegrenzt gueltigen Klartext-Keys aus {@code my_user_entity.autologin_key} wird hier ein
 * kryptografisch signiertes, ablaufendes und JEDERZEIT WIDERRUFBARES ApiToken-JWT
 * ({@link IApiTokenService#validateToken}) geprueft. Bei Erfolg wird eine Browser-Session aufgebaut,
 * damit scriptgesteuerte/Kiosk-Aufrufer (PageTester, ZAP, Turnier-Kiosk) sich durch JSF-Seiten
 * klicken koennen (ApiToken authentisiert sonst nur per Request-Bearer, ohne Session).</p>
 *
 * <p><b>Absicherung (Karte 309):</b></p>
 * <ul>
 *   <li>Der Session-Aufbau laeuft ueber {@link SessionLoginFinalizer} und damit ueber dieselben
 *       Gates wie der Form-Login: Session-Id-Erneuerung (Session-Fixation), Account-Lockout und
 *       zweiter Faktor (TOTP). Vorher wurde der {@link SecurityContextRepository} direkt beschrieben
 *       und alle drei uebersprungen.</li>
 *   <li>Der {@code scope}-Claim des Tokens wird <b>erzwungen</b>
 *       ({@code plaintext.security.token-login.required-scopes}, Default {@code SESSION} — seit
 *       Karte 544 ohne {@code ADMIN}, siehe dort).
 *       Ein fehlender Claim wird abgelehnt (fail-closed). Vorher vergab der Controller pauschal die
 *       vollen DB-Rollen des Token-Besitzers — ein fuer Automation ausgestelltes {@code READ}-Token
 *       ergab damit eine Vollzugriffs-Browser-Session.</li>
 *   <li>Der Endpunkt laesst sich betrieblich abschalten
 *       ({@code plaintext.security.token-login.enabled}).</li>
 *   <li>Zusaetzlich wird geprueft, dass das Mandat im Token zum tatsaechlichen Mandat des Users passt
 *       (Defense-in-Depth).</li>
 * </ul>
 *
 * <p>Der Token reist als Query-Param (bewusst, fuer bookmark-/scriptbare Aufrufer) und wird deshalb
 * im Log NUR maskiert; die Antwort ist {@code no-store}.</p>
 */
@Controller
@Slf4j
public class TokenLoginController {

    private final IApiTokenService apiTokenService;
    private final UserDetailsService userDetailsService;
    private final MyUserRepository userRepository;
    private final SessionLoginFinalizer sessionLoginFinalizer;
    private final PlaintextSecurityProperties securityProperties;

    public TokenLoginController(IApiTokenService apiTokenService,
                                UserDetailsService userDetailsService,
                                MyUserRepository userRepository,
                                SessionLoginFinalizer sessionLoginFinalizer,
                                PlaintextSecurityProperties securityProperties) {
        this.apiTokenService = apiTokenService;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.sessionLoginFinalizer = sessionLoginFinalizer;
        this.securityProperties = securityProperties;
    }

    /** Maskiert das Token fuers Logging (keine Klartext-JWTs in Logs/Graylog). */
    private static String mask(String token) {
        if (token == null || token.isBlank()) {
            return "(leer)";
        }
        return token.length() <= 8 ? "****" : token.substring(0, 8) + "… (len " + token.length() + ")";
    }

    @GetMapping("/token-login")
    public String tokenLogin(@RequestParam(required = false) String token,
                             HttpServletRequest request,
                             HttpServletResponse response) {

        // Token reist als URL-Query-Param (scriptgesteuerte/Kiosk-Aufrufer). Zumindest das Caching
        // der Antwort selbst verhindern.
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");

        if (!securityProperties.getTokenLogin().isEnabled()) {
            log.warn("Token-Login: Endpunkt ist deaktiviert (plaintext.security.token-login.enabled=false)");
            return "redirect:/login.html";
        }

        log.info("Token-Login requested with token: {}", mask(token));

        if (token == null || token.isEmpty()) {
            log.warn("Token-Login: kein Token angegeben");
            return "redirect:/login.html";
        }

        try {
            // Kryptografische Pruefung: Signatur (RSA/PKI), Ablauf UND Revocation.
            Optional<ApiTokenValidationResult> validation = apiTokenService.validateToken(token);
            if (validation.isEmpty()) {
                log.warn("Token-Login: ungueltiges/abgelaufenes/widerrufenes Token: {}", mask(token));
                return "redirect:/login.html";
            }
            ApiTokenValidationResult result = validation.get();

            // Scope-Zwang (fail-closed): nur ausdruecklich fuer eine Session gedachte Tokens duerfen
            // hier eine Browser-Session mit den DB-Rollen des Users eroeffnen.
            if (!scopeErlaubt(result.scope())) {
                log.warn("Token-Login: Scope '{}' erlaubt keinen Session-Aufbau (erlaubt: {}), Token '{}'",
                        result.scope(), securityProperties.getTokenLogin().getRequiredScopes(), result.tokenName());
                return "redirect:/login.html";
            }

            MyUserEntity user = userRepository.findById(result.userId()).orElse(null);
            if (user == null) {
                log.warn("Token-Login: kein User zu userId {} gefunden", result.userId());
                return "redirect:/login.html";
            }

            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());

            // Defense-in-Depth: das Mandat im (signierten) Token muss zum tatsaechlichen Mandat des
            // Users passen. Verhindert einen Session-Aufbau, falls ein User nachtraeglich das Mandat
            // gewechselt haette, das Token aber noch auf das alte lautet.
            String tokenMandat = result.mandat() != null ? result.mandat().toLowerCase() : null;
            String userMandat = extractMandat(userDetails);
            if (tokenMandat != null && !tokenMandat.equals(userMandat)) {
                log.warn("Token-Login: Mandat-Mismatch (Token={}, User={}) fuer {}", tokenMandat, userMandat,
                        user.getUsername());
                return "redirect:/login.html";
            }

            // Karte 309: Lockout, Session-Erneuerung, 2FA-Gate, Startseite und Login-Event zentral --
            // exakt wie beim Form-Login. Der SuccessHandler schreibt den Redirect selbst.
            sessionLoginFinalizer.finalizeLogin(userDetails, userDetails.getAuthorities(),
                    "Token-Login (Token '" + result.tokenName() + "')", request, response);
            return null;

        } catch (AccountStatusException e) {
            log.warn("Token-Login abgelehnt (Account-Status): {}", e.getMessage());
            return "redirect:/login.html";
        } catch (Exception e) {
            log.error("Token-Login fehlgeschlagen", e);
            return "redirect:/login.html";
        }
    }

    /**
     * Fail-closed-Pruefung des {@code scope}-Claims: {@code null}/leer wird abgelehnt, ansonsten muss
     * der Wert (case-insensitiv) in {@code plaintext.security.token-login.required-scopes} stehen.
     */
    private boolean scopeErlaubt(String scope) {
        if (scope == null || scope.isBlank()) {
            return false;
        }
        String normalisiert = scope.trim().toUpperCase();
        return securityProperties.getTokenLogin().getRequiredScopes().stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(s -> s.trim().toUpperCase().equals(normalisiert));
    }

    private String extractMandat(UserDetails userDetails) {
        for (GrantedAuthority ga : userDetails.getAuthorities()) {
            String a = ga.getAuthority();
            if (a != null && a.startsWith("PROPERTY_MANDAT_")) {
                return a.substring("PROPERTY_MANDAT_".length()).toLowerCase();
            }
        }
        return "default";
    }
}

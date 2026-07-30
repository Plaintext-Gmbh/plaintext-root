/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import ch.plaintext.boot.plugins.security.PlaintextLoginEvent;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * ({@link IApiTokenService#validateToken}) geprueft. Bei Erfolg wird — 1:1 wie im
 * {@code AutoLoginController} — eine Browser-Session aufgebaut ({@link SecurityContextRepository}),
 * damit scriptgesteuerte/Kiosk-Aufrufer (PageTester, ZAP, Turnier-Kiosk) sich durch JSF-Seiten
 * klicken koennen (ApiToken authentisiert sonst nur per Request-Bearer, ohne Session).</p>
 *
 * <p><b>Sicherheit:</b> Der Zugang wird ueber den Widerruf des Tokens gesteuert (kein separates
 * Enabled-Flag noetig). Der Token reist als Query-Param (bewusst, fuer bookmark-/scriptbare Aufrufer)
 * und wird deshalb im Log NUR maskiert; die Antwort ist {@code no-store}. Zusaetzlich wird geprueft,
 * dass das Mandat im Token zum tatsaechlichen Mandat des Users passt (Defense-in-Depth).</p>
 */
@Controller
@Slf4j
public class TokenLoginController {

    private final IApiTokenService apiTokenService;
    private final UserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository;
    private final MyUserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public TokenLoginController(IApiTokenService apiTokenService,
                                UserDetailsService userDetailsService,
                                SecurityContextRepository securityContextRepository,
                                MyUserRepository userRepository,
                                ApplicationEventPublisher eventPublisher) {
        this.apiTokenService = apiTokenService;
        this.userDetailsService = userDetailsService;
        this.securityContextRepository = securityContextRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
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

            // Session aufbauen (1:1 wie AutoLoginController). 3-arg-Konstruktor setzt authenticated=true.
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authToken);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            log.info("Token-Login erfolgreich fuer User: {} (Token '{}')", user.getUsername(), result.tokenName());

            // Login-Event (generischer Hook, wie im AutoLoginController).
            try {
                Long userId = extractUserId(userDetails);
                String baseUrl = extractBaseUrl(request);
                eventPublisher.publishEvent(new PlaintextLoginEvent(this, user.getUsername(), userId,
                        user.getUsername(), userMandat, baseUrl));
            } catch (Exception e) {
                log.warn("Token-Login: publish login event fehlgeschlagen: {}", e.getMessage());
            }

            String redirectUrl = "index.html";
            if (user.getStartpage() != null && !user.getStartpage().isEmpty()) {
                redirectUrl = user.getStartpage();
            }
            return "redirect:/" + redirectUrl;

        } catch (Exception e) {
            log.error("Token-Login fehlgeschlagen", e);
            return "redirect:/login.html";
        }
    }

    private Long extractUserId(UserDetails userDetails) {
        for (GrantedAuthority ga : userDetails.getAuthorities()) {
            String a = ga.getAuthority();
            if (a != null && a.startsWith("PROPERTY_MYUSERID_")) {
                try {
                    return Long.parseLong(a.substring("PROPERTY_MYUSERID_".length()));
                } catch (NumberFormatException e) { /* ignore */ }
            }
        }
        return -1L;
    }

    private String extractBaseUrl(HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) scheme = request.getScheme();
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null) host = request.getServerName();
        int port = request.getServerPort();
        String forwardedPort = request.getHeader("X-Forwarded-Port");
        if (forwardedPort != null) {
            try { port = Integer.parseInt(forwardedPort); } catch (NumberFormatException e) { /* ignore */ }
        }
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
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

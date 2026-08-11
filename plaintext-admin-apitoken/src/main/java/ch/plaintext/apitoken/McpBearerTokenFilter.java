/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.McpUserRoles;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Kanonischer Bearer-Token-Filter für MCP-/Token-REST-Endpoints ({@code /mcp/*} u.ä.).
 *
 * <p>Zentralisiert die bisher in plaintext-app, plaintext-iot und plaintext-schuetu divergent
 * kopierten {@code McpBearerTokenFilter}-Implementierungen. Die Endpoints sind in der
 * Spring-Security-Chain als {@code permitAll} eingetragen (siehe
 * {@code plaintext.security.permitAllPatterns}), damit der Request bis zum Servlet durchkommt;
 * die eigentliche Authentisierung übernimmt DIESER Filter. Er wird per
 * {@link org.springframework.boot.web.servlet.FilterRegistrationBean} (siehe
 * {@link McpBearerTokenFilterConfig}, Property-gesteuert) registriert, validiert den
 * {@code Bearer}-Token und liefert bei fehlendem/ungültigem Token 401 JSON, ohne die
 * Filterkette fortzusetzen.</p>
 *
 * <p><b>Validierungs-Strategien</b> (konfigurierbar, siehe {@link McpBearerTokenFilterProperties}):</p>
 * <ul>
 *   <li>{@link #jwtOnly(JwtTokenService, McpUserRoles)} — reine JWT-Signatur/Expiry-Prüfung ohne
 *       DB-Zugriff. Historisch der Workaround für den Hikari-Connection-Leak des damals noch
 *       {@code @Transactional} annotierten {@link ApiTokenService#validateToken(String)}.
 *       Trade-off: Token-Revocation greift serverseitig erst mit dem JWT-Expiry.</li>
 *   <li>{@link #withRevocationCheck(IApiTokenService, McpUserRoles)} — vollständige Validierung
 *       inkl. DB-Revocation-Check. Seit dem Leak-Fix in {@link ApiTokenService#validateToken(String)}
 *       (nicht mehr {@code @Transactional}, root ≥ 1.246.0) auch aus Servlet-Filtern leak-frei
 *       aufrufbar und daher die empfohlene Strategie.</li>
 * </ul>
 *
 * <p><b>SecurityContext-Hygiene:</b> Der Filter mutiert NIE das bestehende
 * {@link SecurityContext}-Objekt in-place. Wird der Request mit einem HTTP-Session-Cookie
 * geschickt (z.B. SPAs, die eine Token-REST-API MIT dem Session-Cookie des eingeloggten Users
 * pollen), hält {@code HttpSessionSecurityContextRepository} genau diese Context-Referenz — ein
 * {@code getContext().setAuthentication(...)} würde die Session-Authentication in-place auf das
 * reduzierte Token-Set überschreiben und dem User seine echten Rollen klauen. Daher wird ein
 * frischer Context gesetzt und im {@code finally} der ursprüngliche wiederhergestellt; so bleibt
 * auch auf gepoolten Tomcat-Threads keine Token-Authentication zurück.</p>
 *
 * <p><b>Rollen:</b> Das MCP-JWT trägt bewusst KEINE Rollen. Damit
 * {@code PlaintextSecurity#getAllowedMandate()} ROOT erkennt, lädt der Filter die echten Rollen
 * des Token-Users leak-frei per {@link McpUserRoles} und legt sie als
 * {@code ROLE_*}/{@code PROPERTY_*}-Authorities ab.</p>
 *
 * <p><b>Scope:</b> Trägt das Token einen {@code scope}-Claim ({@code READ}/{@code WRITE}/
 * {@code ADMIN}, siehe {@link JwtTokenService#generateToken(Long, String, String, String, int, String)}),
 * vergibt der Filter KUMULATIVE {@code SCOPE_*}-Authorities (ADMIN erhält auch SCOPE_WRITE +
 * SCOPE_READ, WRITE auch SCOPE_READ) — Downstream-Apps prüfen dann per {@code @PreAuthorize
 * ("hasAuthority('SCOPE_WRITE')")} o.ä., ohne hasAnyAuthority-Ketten. <b>Fehlt der Claim, gilt seit
 * Karte 312 nur noch {@code READ}</b> (fail-closed). Zuvor galt {@code ADMIN} als „sanfte Migration" —
 * da die Token-Ausstellung damals gar keinen Scope vergab, war damit faktisch jeder API-Token ein
 * Vollzugriffs-Token. Wer scope-lose Alt-Tokens noch produktiv im Einsatz hat, kann das alte Verhalten
 * über {@code plaintext.mcp.bearer-filter.legacy-scope-admin=true} befristet zurückholen.</p>
 *
 * <p><b>Revocation:</b> Trägt das Token einen {@code jti}-Claim UND ist ein {@link JtiRevocationChecker}
 * verfügbar (optionaler Collaborator, siehe {@link McpBearerTokenFilterConfig}), wird die Anfrage bei
 * gesperrtem Token wie ein ungültiges Token mit 401 abgelehnt. Ohne Checker (Default) oder ohne
 * {@code jti}-Claim (Alt-Token) findet keine Revocation-Prüfung statt.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
public class McpBearerTokenFilter implements Filter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenValidator tokenValidator;
    private final McpUserRoles mcpUserRoles;
    private final JtiRevocationChecker revocationChecker;

    /**
     * Alt-Verhalten für Tokens ohne {@code scope}-Claim (Karte 312, H-7): {@code true} deutet einen
     * fehlenden Scope weiterhin als {@code ADMIN}, {@code false} (Default) vergibt nur {@code READ}.
     * Wird aus {@code plaintext.mcp.bearer-filter.legacy-scope-admin} gesetzt; als Setter statt
     * Konstruktor-Parameter, damit die bestehenden Factories/Konstruktoren unverändert bleiben.
     */
    @Setter
    private boolean legacyScopeAdmin = false;

    /**
     * @param tokenValidator    Validierungs-Strategie für den rohen Bearer-Token (ohne Prefix)
     * @param mcpUserRoles      leak-freier Rollen-Lookup für den Token-User
     * @param revocationChecker optionale jti-Blocklist-Prüfung (nie {@code null} — no-op-Default
     *                          über die statischen Factories, wenn keine App-Bean vorhanden ist)
     */
    public McpBearerTokenFilter(TokenValidator tokenValidator, McpUserRoles mcpUserRoles, JtiRevocationChecker revocationChecker) {
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator");
        this.mcpUserRoles = Objects.requireNonNull(mcpUserRoles, "mcpUserRoles");
        this.revocationChecker = Objects.requireNonNull(revocationChecker, "revocationChecker");
    }

    /**
     * JWT-only Validierung (Signatur + Expiry, KEIN DB-Zugriff, keine Revocation vor Expiry) —
     * das bisherige Verhalten der app-/schuetu-Kopien, ohne jti-Blocklist-Prüfung.
     */
    public static McpBearerTokenFilter jwtOnly(JwtTokenService jwtTokenService, McpUserRoles mcpUserRoles) {
        return jwtOnly(jwtTokenService, mcpUserRoles, jti -> false);
    }

    /** Wie {@link #jwtOnly(JwtTokenService, McpUserRoles)}, zusätzlich mit jti-Blocklist-Prüfung. */
    public static McpBearerTokenFilter jwtOnly(JwtTokenService jwtTokenService, McpUserRoles mcpUserRoles,
                                                JtiRevocationChecker revocationChecker) {
        Objects.requireNonNull(jwtTokenService, "jwtTokenService");
        return new McpBearerTokenFilter(
                token -> jwtTokenService.validateToken(token)
                        .map(r -> new ValidatedToken(r.userId(), r.mandat(), r.email(), r.scope(), r.jti())),
                mcpUserRoles, revocationChecker);
    }

    /**
     * Vollständige Validierung inkl. DB-Revocation-Check — das bisherige Verhalten der iot-Kopie
     * und die empfohlene Strategie.
     * <p>
     * <b>Nicht mehr „leak-frei seit root ≥ 1.246.0" (Karte 655, 11.08.2026).</b> Der damalige Fix
     * beseitigte das {@code @Transactional}-Leck; das view-gebundene bleibt: Der OSIV-Filter
     * umschliesst diese Filterkette, deshalb haelt der JPA-Revocation-Lookup seine DB-Verbindung
     * bis zum Requestende — bei einer MCP-Sitzung also ueber deren ganze Laufzeit. Gemessen in
     * plaintext-iot: 15 {@code Apparent connection leak detected} in 7 Tagen mit
     * {@code ApiTokenService.validateVerifiedToken} im Stack. Einordnung und Begruendung, warum
     * das (noch) nicht umgebaut wird, im Javadoc von {@link ApiTokenService#validateToken}. Ohne jti-Blocklist-Prüfung
     * (die Hash-Allowlist in {@link ApiTokenService} deckt Revocation hier bereits ab; von dieser
     * Strategie geminzte Tokens tragen aktuell keinen scope/jti-Claim).
     */
    public static McpBearerTokenFilter withRevocationCheck(IApiTokenService apiTokenService, McpUserRoles mcpUserRoles) {
        return withRevocationCheck(apiTokenService, mcpUserRoles, jti -> false);
    }

    /** Wie {@link #withRevocationCheck(IApiTokenService, McpUserRoles)}, zusätzlich mit jti-Blocklist-Prüfung. */
    public static McpBearerTokenFilter withRevocationCheck(IApiTokenService apiTokenService, McpUserRoles mcpUserRoles,
                                                             JtiRevocationChecker revocationChecker) {
        Objects.requireNonNull(apiTokenService, "apiTokenService");
        return new McpBearerTokenFilter(
                token -> apiTokenService.validateToken(token)
                        // scope MUSS durchgereicht werden (Karte 349): sonst käme jedes Token in dieser
                        // Strategie ohne Claim an und der fail-closed-Default degradierte es auf READ —
                        // ein Umstellen auf validation: DATABASE würde damit alle EINTRAGEN-Flows
                        // (Zeiterfassung-Uhr, Juriwagen) stillschweigend auf Lesezugriff kappen.
                        .map(r -> new ValidatedToken(r.userId(), r.mandat(), r.email(), r.scope(), null)),
                mcpUserRoles, revocationChecker);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            unauthorized(httpResponse);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        Optional<ValidatedToken> result = tokenValidator.validate(token);
        if (result.isEmpty()) {
            unauthorized(httpResponse);
            return;
        }

        ValidatedToken validation = result.get();
        if (validation.jti() != null && revocationChecker.isRevoked(validation.jti())) {
            log.warn("MCP request rejected: token jti={} (userId={}, mandat={}) is revoked",
                    validation.jti(), validation.userId(), validation.mandat());
            unauthorized(httpResponse);
            return;
        }

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        authorities.add(new SimpleGrantedAuthority("PROPERTY_MYUSERID_" + validation.userId()));
        authorities.add(new SimpleGrantedAuthority("PROPERTY_MANDAT_" + validation.mandat()));
        addScopeAuthorities(authorities, validation.scope());
        // Echte Rollen des Users (ROOT/ADMIN/PROPERTY_MANDAT_* etc.) — damit getAllowedMandate() stimmt.
        for (String role : mcpUserRoles.rolesForUser(validation.userId())) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String r = role.toUpperCase();
            authorities.add(new SimpleGrantedAuthority(r.startsWith("PROPERTY_") ? r : "ROLE_" + r));
        }
        var auth = new UsernamePasswordAuthenticationToken(validation.email(), null, authorities);

        // WICHTIG: NICHT das bestehende SecurityContext-Objekt mutieren (Session-Kontext-Schutz,
        // siehe Klassen-Javadoc). Frischen Context setzen, im finally den vorherigen restaurieren —
        // das räumt zugleich die Token-Authentication defensiv vom gepoolten Thread.
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext bearerContext = SecurityContextHolder.createEmptyContext();
        bearerContext.setAuthentication(auth);
        SecurityContextHolder.setContext(bearerContext);
        try {
            log.debug("MCP request authenticated for user {} mandat={}", validation.userId(), validation.mandat());
            chain.doFilter(request, response);
        } catch (AccessDeniedException e) {
            // SECURITY/API-VERTRAG (Karte 652): Ein Bearer-Client, dem ein Recht fehlt, bekam bisher
            // HTTP 302 auf /login.html — HTML fuer ein Geraet, das JSON erwartet. Gemessen an
            // schuetu INT am 11.08.2026: READ-Token gegen
            // @PreAuthorize("hasAuthority('SCOPE_WRITE')") -> 302; mit `curl -L` wird daraus
            // HTTP 200 mit 14 534 Bytes Anmeldeseite, also ein Erfolg im aufrufenden Skript.
            //
            // Ursache ist die POSITION dieses Filters, nicht die Security-Konfiguration: Er laeuft
            // mit order=1 HINTER der Security-Kette (-100). Die AccessDeniedException aus
            // @PreAuthorize passiert auf dem Rueckweg zuerst das finally unten — das den
            // vorherigen, anonymen Context wiederherstellt — und erst danach den
            // ExceptionTranslationFilter. Der entscheidet aber genau an dieser Authentication
            // zwischen 403 (angemeldet, kein Recht) und 302 auf die Anmeldung (anonym). Der Filter
            // hatte seine eigene Authentication also weggeraeumt, bevor sie beurteilt werden
            // konnte.
            //
            // Deshalb schliesst der Fall hier ab, wo er entstanden ist: dieselbe JSON-Form wie das
            // 401 oben, und nur fuer die url-patterns dieses Filters — JSF-/Browser-Pfade kommen
            // hier nie vorbei und leiten weiterhin auf /login.html um.
            if (httpResponse.isCommitted()) {
                // Antwort laeuft bereits (z.B. eine SSE-Verbindung unter /mcp): Ein nachgeschobener
                // JSON-Rumpf wuerde den Datenstrom zerstoeren. Dann lieber die Exception weiterreichen.
                throw e;
            }
            log.warn("MCP request abgewiesen (Recht fehlt): {} {} — userId={}, scope={} → 403 JSON",
                    httpRequest.getMethod(), httpRequest.getRequestURI(),
                    validation.userId(), validation.scope());
            forbidden(httpResponse);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Valid Bearer token required\"}");
    }

    /**
     * Gegenstueck zu {@link #unauthorized(HttpServletResponse)} fuer den Fall „Token gueltig, Recht
     * fehlt" (Karte 652). Bewusst ohne Angabe, welcher Scope gefehlt hat: Der Client kann daran
     * nichts aendern, und die Endpunkt-Rechtematrix gehoert nicht in eine Fehlerantwort.
     */
    private void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Token lacks the required scope for this endpoint\"}");
    }

    /**
     * Vergibt kumulative {@code SCOPE_*}-Authorities: SCOPE_READ immer, SCOPE_WRITE <b>und</b>
     * SCOPE_EINTRAGEN zusätzlich bei WRITE/EINTRAGEN/ADMIN, SCOPE_ADMIN zusätzlich bei ADMIN. Ein
     * nicht erkannter Claim-Wert bekommt NUR SCOPE_READ (least privilege).
     *
     * <p><b>Übergangsfenster (Karte 545):</b> {@code WRITE} ist der neue Name des bisherigen Scopes
     * {@code EINTRAGEN} (Entscheid Daniel, 05.08.2026). Beide Claim-Werte sind gültig und vergeben
     * <em>dieselben</em> Authorities — sonst prüfte die eine Hälfte des Codes gegen SCOPE_EINTRAGEN
     * und die andere gegen SCOPE_WRITE, und ein Token bestünde nur die halbe Strecke. Der Altname
     * fällt erst, wenn alle Downstream-{@code @PreAuthorize}-Ausdrücke auf {@code SCOPE_WRITE}
     * stehen (Stufe 3 der Karte).</p>
     *
     * <p><b>Fehlender/leerer Claim ⇒ {@code READ} (Karte 312, H-7).</b> Vorher galt hier {@code ADMIN}
     * als „sanfte Migration" — in Kombination damit, dass die Token-Ausstellung gar keinen Scope
     * vergab, war dadurch faktisch <em>jeder</em> API-Token ein Vollzugriffs-Token. Wer das Alt-
     * Verhalten befristet braucht, setzt {@code plaintext.mcp.bearer-filter.legacy-scope-admin=true}
     * (siehe {@link McpBearerTokenFilterProperties#isLegacyScopeAdmin()}).</p>
     */
    private void addScopeAuthorities(Set<GrantedAuthority> authorities, String scope) {
        String fallback = legacyScopeAdmin ? "ADMIN" : "READ";
        String effective = (scope == null || scope.isBlank()) ? fallback : scope.trim().toUpperCase();
        authorities.add(new SimpleGrantedAuthority("SCOPE_READ"));
        if (effective.equals("WRITE") || effective.equals("EINTRAGEN") || effective.equals("ADMIN")) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_WRITE"));
            authorities.add(new SimpleGrantedAuthority("SCOPE_EINTRAGEN"));
        }
        if (effective.equals("ADMIN")) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_ADMIN"));
        }
    }

    /**
     * Validierungs-Strategie: roher Bearer-Token (ohne {@code "Bearer "}-Prefix) rein,
     * validierter Principal raus — oder {@link Optional#empty()} bei ungültigem Token.
     */
    @FunctionalInterface
    public interface TokenValidator {
        Optional<ValidatedToken> validate(String token);
    }

    /**
     * Minimaler validierter Principal, den der Filter für Authentication + Authorities braucht.
     *
     * @param userId Benutzer-ID aus dem Token
     * @param mandat Mandat aus dem Token
     * @param email  E-Mail des Token-Users (wird Principal-Name)
     * @param scope  {@code READ}/{@code EINTRAGEN}/{@code ADMIN}, oder {@code null} (Alt-Token ohne
     *               scope-Claim; dann greift der fail-closed-Default)
     * @param jti    Token-ID für die Revocation-Prüfung, oder {@code null} (Alt-Token bzw.
     *               DATABASE-Strategie, die eigene Hash-basierte Revocation nutzt)
     */
    public record ValidatedToken(Long userId, String mandat, String email, String scope, String jti) {}
}

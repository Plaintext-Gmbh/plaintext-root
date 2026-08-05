/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * MCP-Tools zum <b>regulären</b> Ausstellen, Auflisten und Widerrufen von API-Tokens (Karte 349,
 * Entscheid „Weg B").
 *
 * <p><b>Warum es das braucht:</b> Bisher gab es zum Ausstellen eines API-Tokens nur die JSF-UI
 * (Login nötig). Maschinelle Zugänge — auch die MCP-Zugänge selbst — wurden deshalb direkt mit dem
 * Signing-Key gemintet und hatten <em>keine</em> {@code api_token}-Zeile. Ein Umstellen des
 * Bearer-Filters auf {@code plaintext.mcp.bearer-filter.validation: DATABASE} hätte genau diese
 * Zugänge ausgesperrt. Über dieses Tool bekommt jeder ausgestellte Token den regulären Weg über
 * {@link ApiTokenService#createToken(Long, String, String, String, int, String)}: DB-Zeile mit
 * SHA-256-Hash, explizitem Scope, Ablaufdatum — und ist damit auch widerrufbar.</p>
 *
 * <p><b>Autorisierung (bewusst eng):</b></p>
 * <ul>
 *   <li>Der Aufrufer muss über den {@link McpBearerTokenFilter} authentisiert sein — der legt
 *       {@code PROPERTY_MYUSERID_*}, {@code PROPERTY_MANDAT_*}, die echten Benutzerrollen und die
 *       {@code SCOPE_*}-Authorities des Tokens in den SecurityContext.</li>
 *   <li>Es braucht {@code SCOPE_ADMIN} <b>und</b> die Rolle {@code ADMIN} oder {@code ROOT}. Beides
 *       zusammen, weil beides Unterschiedliches absichert: Der Scope verhindert, dass ein READ-Token
 *       sich selbst zu einem ADMIN-Token hochschreibt (Rechteausweitung über die Ausstellung); die
 *       Rolle verhindert, dass ein beliebiger Benutzer maschinelle Zugänge anlegt.</li>
 *   <li>Ausgestellt wird <b>ausschliesslich für den aufrufenden Benutzer im eigenen Mandanten</b>.
 *       Ein {@code userId}-Parameter wäre ein Impersonation-Vektor — wer für andere ausstellen will,
 *       nimmt die UI.</li>
 *   <li>Der {@code scope} ist ein <b>Pflichtparameter</b> ohne stillen Default. Ein Default auf ADMIN
 *       war genau der Fehler aus Karte 312 (fehlender Claim ⇒ Vollzugriff).</li>
 * </ul>
 *
 * <p>{@link ConditionalOnClass} auf die MCP-Annotation: Das Bean lädt nur in Apps mit eigenem
 * spring-ai-MCP-Server (app/guild/schuetu/iot). Apps ohne MCP — z.B. plaintext-root selbst —
 * bleiben unberührt, die Dependency ist optional und damit nicht transitiv.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springaicommunity.mcp.annotation.McpTool")
public class ApiTokenMcpTools {

    private static final Set<String> ERLAUBTE_SCOPES = Set.of("READ", "EINTRAGEN", "ADMIN");
    private static final String SCOPE_ADMIN = "SCOPE_ADMIN";
    private static final Set<String> AUSSTELLER_ROLLEN = Set.of("ROLE_ADMIN", "ROLE_ROOT");
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ApiTokenService apiTokenService;

    @McpTool(name = "create_api_token",
            description = "Stellt einen neuen API-Token (JWT) fuer den AUFRUFENDEN Benutzer aus — regulaer "
            + "ueber den ApiTokenService, d.h. mit Zeile in api_token (nur SHA-256-Hash), explizitem Scope, "
            + "Ablaufdatum und Widerrufsmoeglichkeit. Der Token-String wird GENAU EINMAL zurueckgegeben und "
            + "ist danach nicht wiederherstellbar. Erfordert einen Aufrufer-Token mit Scope ADMIN sowie die "
            + "Rolle ADMIN oder ROOT. scope ist PFLICHT: READ, EINTRAGEN oder ADMIN.")
    public String createApiToken(
            @McpToolParam(description = "Name des Tokens, z.B. 'mcpZorin' (muss im Mandanten des Benutzers "
                    + "eindeutig sein — bestehenden zuerst per revoke_api_token widerrufen)") String tokenName,
            @McpToolParam(description = "Berechtigungsumfang, PFLICHT: READ, EINTRAGEN oder ADMIN") String scope,
            // Karte 520: Der Code liest den Parameter ausdruecklich als weglassbar
            // ("validityDays == null ? DEFAULT_VALIDITY_DAYS : validityDays" drei Zeilen weiter
            // unten), das Schema fuehrte ihn aber als Pflicht.
            @McpToolParam(required = false, description = "Gueltigkeit in Tagen (7-365, leer = 90)")
            Integer validityDays) {

        Aufrufer aufrufer;
        try {
            aufrufer = aufruferPruefen();
        } catch (ZugriffVerweigert e) {
            return e.getMessage();
        }

        if (tokenName == null || tokenName.isBlank()) {
            return "FEHLER: tokenName fehlt.";
        }
        if (scope == null || scope.isBlank()) {
            return "FEHLER: scope fehlt — erlaubt: READ, EINTRAGEN, ADMIN. Es gibt bewusst keinen Default.";
        }
        String normalisierterScope = scope.trim().toUpperCase(Locale.ROOT);
        if (!ERLAUBTE_SCOPES.contains(normalisierterScope)) {
            return "FEHLER: ungueltiger scope '" + scope + "' — erlaubt: READ, EINTRAGEN, ADMIN.";
        }
        int tage = validityDays == null ? JwtTokenService.DEFAULT_VALIDITY_DAYS : validityDays;
        if (tage < JwtTokenService.MIN_VALIDITY_DAYS || tage > JwtTokenService.MAX_VALIDITY_DAYS) {
            return "FEHLER: validityDays muss zwischen " + JwtTokenService.MIN_VALIDITY_DAYS + " und "
                    + JwtTokenService.MAX_VALIDITY_DAYS + " liegen.";
        }

        try {
            String jwt = apiTokenService.createToken(aufrufer.userId(), aufrufer.mandat(), tokenName.trim(),
                    aufrufer.email(), tage, normalisierterScope);
            log.info("MCP: API-Token '{}' ausgestellt (userId={}, mandat={}, scope={}, {} Tage)",
                    tokenName, aufrufer.userId(), aufrufer.mandat(), normalisierterScope, tage);
            return "OK: Token '" + tokenName.trim() + "' ausgestellt (scope=" + normalisierterScope
                    + ", " + tage + " Tage). Der Token wird nur JETZT angezeigt:\n" + jwt;
        } catch (RuntimeException e) {
            log.warn("MCP: Ausstellung von '{}' fehlgeschlagen: {}", tokenName, e.getMessage());
            return "FEHLER: " + e.getMessage();
        }
    }

    @McpTool(name = "list_api_tokens",
            description = "Listet die aktiven API-Tokens des aufrufenden Benutzers (Id, Name, Ablauf, "
            + "letzte Nutzung) — ohne Token-Strings, die sind nicht wiederherstellbar. Erfordert Scope ADMIN "
            + "sowie die Rolle ADMIN oder ROOT.")
    public String listApiTokens() {
        Aufrufer aufrufer;
        try {
            aufrufer = aufruferPruefen();
        } catch (ZugriffVerweigert e) {
            return e.getMessage();
        }

        List<ApiToken> tokens = apiTokenService.getAllTokens(aufrufer.userId(), aufrufer.mandat());
        if (tokens.isEmpty()) {
            return "Keine aktiven Tokens.";
        }
        StringBuilder sb = new StringBuilder("Aktive Tokens (mandat=" + aufrufer.mandat() + "):\n");
        for (ApiToken t : tokens) {
            sb.append("- id=").append(t.getId())
                    .append(" name=").append(t.getTokenName())
                    .append(" laeuft_ab=").append(t.getExpiresAt() == null ? "-" : DATUM.format(t.getExpiresAt()))
                    .append(" zuletzt_genutzt=")
                    .append(t.getLastUsedAt() == null ? "nie" : DATUM.format(t.getLastUsedAt()))
                    .append(t.isInvalidated() ? " WIDERRUFEN" : "")
                    .append('\n');
        }
        return sb.toString();
    }

    @McpTool(name = "revoke_api_token",
            description = "Widerruft (invalidiert) einen API-Token des AUFRUFENDEN Benutzers anhand seiner Id "
            + "aus list_api_tokens. Der Token wird sofort ungueltig, sobald der Bearer-Filter im "
            + "DATABASE-Modus laeuft. Erfordert Scope ADMIN sowie die Rolle ADMIN oder ROOT.")
    public String revokeApiToken(
            @McpToolParam(description = "Token-Id aus list_api_tokens") Long tokenId) {
        Aufrufer aufrufer;
        try {
            aufrufer = aufruferPruefen();
        } catch (ZugriffVerweigert e) {
            return e.getMessage();
        }
        if (tokenId == null) {
            return "FEHLER: tokenId fehlt.";
        }

        boolean vorhanden = apiTokenService.getAllTokens(aufrufer.userId(), aufrufer.mandat()).stream()
                .anyMatch(t -> tokenId.equals(t.getId()));
        if (!vorhanden) {
            // Bewusst keine Auskunft darueber, ob die Id anderswo existiert.
            return "FEHLER: Token " + tokenId + " nicht gefunden (oder gehoert nicht dir).";
        }

        apiTokenService.invalidateToken(tokenId, aufrufer.userId(), aufrufer.mandat());
        log.info("MCP: API-Token id={} widerrufen (userId={}, mandat={})",
                tokenId, aufrufer.userId(), aufrufer.mandat());
        return "OK: Token " + tokenId + " widerrufen.";
    }

    /**
     * Prüft die Autorisierung des Aufrufers und liefert dessen Identität aus dem SecurityContext.
     *
     * @throws ZugriffVerweigert wenn keine Authentication vorliegt, {@code SCOPE_ADMIN} bzw. die Rolle
     *                           ADMIN/ROOT fehlt, oder Identität/Mandant nicht bestimmbar sind
     */
    private Aufrufer aufruferPruefen() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ZugriffVerweigert("FEHLER: nicht authentisiert.");
        }
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        if (!authorities.contains(SCOPE_ADMIN)) {
            throw new ZugriffVerweigert("FEHLER: Token-Ausstellung erfordert einen Aufrufer-Token mit scope=ADMIN.");
        }
        if (AUSSTELLER_ROLLEN.stream().noneMatch(authorities::contains)) {
            throw new ZugriffVerweigert("FEHLER: Token-Ausstellung erfordert die Rolle ADMIN oder ROOT.");
        }

        Long userId = praefixWert(authorities, "PROPERTY_MYUSERID_").map(Long::valueOf)
                .orElseThrow(() -> new ZugriffVerweigert("FEHLER: Benutzer-Id im Sicherheitskontext nicht bestimmbar."));
        String mandat = praefixWert(authorities, "PROPERTY_MANDAT_")
                .orElseThrow(() -> new ZugriffVerweigert("FEHLER: Mandant im Sicherheitskontext nicht bestimmbar."));
        return new Aufrufer(userId, mandat, auth.getName());
    }

    private Optional<String> praefixWert(Set<String> authorities, String praefix) {
        return authorities.stream()
                .filter(a -> a.startsWith(praefix))
                .map(a -> a.substring(praefix.length()))
                .filter(v -> !v.isBlank())
                .findFirst();
    }

    /** Identität des Aufrufers, aus dem vom Bearer-Filter befüllten SecurityContext gelesen. */
    private record Aufrufer(Long userId, String mandat, String email) {}

    /** Interner Abbruch mit fertiger, an den MCP-Client zurückgegebener Meldung. */
    private static class ZugriffVerweigert extends RuntimeException {
        ZugriffVerweigert(String message) {
            super(message);
        }
    }
}

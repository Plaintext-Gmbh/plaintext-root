/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
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
 * MCP tools for <b>regularly</b> issuing, listing and revoking API tokens (card 349,
 * decision "path B").
 *
 * <p><b>Why this is needed:</b> until now the only way to issue an API token was the JSF UI
 * (login required). Machine accesses — including the MCP accesses themselves — were therefore
 * minted directly with the signing key and had <em>no</em> {@code api_token} row. Switching the
 * bearer filter to {@code plaintext.mcp.bearer-filter.validation: DATABASE} would have locked out
 * exactly those accesses. Through this tool every issued token takes the regular path via
 * {@link ApiTokenService#createToken(Long, String, String, String, int, String)}: a DB row with
 * SHA-256 hash, explicit scope, expiry date — and is thereby revocable as well.</p>
 *
 * <p><b>Authorization (deliberately narrow):</b></p>
 * <ul>
 *   <li>The caller must be authenticated through the {@link McpBearerTokenFilter} — which puts
 *       {@code PROPERTY_MYUSERID_*}, {@code PROPERTY_MANDAT_*}, the real user roles and the
 *       token's {@code SCOPE_*} authorities into the SecurityContext.</li>
 *   <li>It requires {@code SCOPE_ADMIN} <b>and</b> the role {@code ADMIN} or {@code ROOT}. Both
 *       together, because each safeguards something different: the scope prevents a READ token
 *       from writing itself up into an ADMIN token (privilege escalation via issuing); the
 *       role prevents an arbitrary user from creating machine accesses.</li>
 *   <li>Tokens are issued <b>exclusively for the calling user within their own tenant</b>.
 *       A {@code userId} parameter would be an impersonation vector — whoever wants to issue for
 *       others uses the UI.</li>
 *   <li>The {@code scope} is a <b>mandatory parameter</b> with no silent default. A default of ADMIN
 *       was exactly the bug from card 312 (missing claim ⇒ full access).</li>
 * </ul>
 *
 * <p>{@link ConditionalOnClass} on the MCP annotation: the bean only loads in apps with their own
 * spring-ai MCP server (app/guild/schuetu/iot). Apps without MCP — e.g. plaintext-root itself —
 * stay untouched, the dependency is optional and therefore not transitive.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springaicommunity.mcp.annotation.McpTool")
public class ApiTokenMcpTools {

    /**
     * Valid scope values. Since card 545, {@code WRITE} is the name of the write permission;
     * {@code EINTRAGEN} stays valid during the transition window (same authorities, see
     * {@link McpBearerTokenFilter}) and is dropped with stage 3 of the card.
     */
    private static final Set<String> ERLAUBTE_SCOPES = Set.of("READ", "WRITE", "EINTRAGEN", "ADMIN");
    private static final String SCOPE_ADMIN = "SCOPE_ADMIN";
    private static final Set<String> AUSSTELLER_ROLLEN = Set.of("ROLE_ADMIN", "ROLE_ROOT");
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ApiTokenService apiTokenService;

    @McpTool(name = "create_api_token",
            description = "Stellt einen neuen API-Token (JWT) fuer den AUFRUFENDEN Benutzer aus — regulaer "
            + "ueber den ApiTokenService, d.h. mit Zeile in api_token (nur SHA-256-Hash), explizitem Scope, "
            + "Ablaufdatum und Widerrufsmoeglichkeit. Der Token-String wird GENAU EINMAL zurueckgegeben und "
            + "ist danach nicht wiederherstellbar. Erfordert einen Aufrufer-Token mit Scope ADMIN sowie die "
            + "Rolle ADMIN oder ROOT. scope ist PFLICHT: READ, WRITE oder ADMIN (EINTRAGEN ist der "
            + "Altname von WRITE und uebergangsweise noch gueltig).")
    public String createApiToken(
            @McpToolParam(description = "Name des Tokens, z.B. 'mcpZorin' (muss im Mandanten des Benutzers "
                    + "eindeutig sein — bestehenden zuerst per revoke_api_token widerrufen)") String tokenName,
            @McpToolParam(description = "Berechtigungsumfang, PFLICHT: READ, WRITE oder ADMIN "
                    + "(EINTRAGEN = Altname von WRITE, uebergangsweise noch gueltig)") String scope,
            // Card 520: the code explicitly reads the parameter as optional
            // ("validityDays == null ? DEFAULT_VALIDITY_DAYS : validityDays" three lines further
            // down), but the schema listed it as mandatory.
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
            // Deliberately no information about whether the id exists elsewhere.
            return "FEHLER: Token " + tokenId + " nicht gefunden (oder gehoert nicht dir).";
        }

        apiTokenService.invalidateToken(tokenId, aufrufer.userId(), aufrufer.mandat());
        log.info("MCP: API-Token id={} widerrufen (userId={}, mandat={})",
                tokenId, aufrufer.userId(), aufrufer.mandat());
        return "OK: Token " + tokenId + " widerrufen.";
    }

    /**
     * Checks the caller's authorization and returns their identity from the SecurityContext.
     *
     * @throws ZugriffVerweigert if no Authentication is present, {@code SCOPE_ADMIN} or the role
     *                           ADMIN/ROOT is missing, or identity/tenant cannot be determined
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
        String mandat = mandatBestimmen(authorities);
        return new Aufrufer(userId, mandat, auth.getName());
    }

    /**
     * Determines the caller's tenant — the one <b>of their token</b>, not just any one from the
     * context (card 670).
     *
     * <p>Why this needs a method of its own: the context regularly holds two
     * {@code PROPERTY_MANDAT_*} authorities, because the same tenant is spelled lower case in the
     * token and upper case in {@code my_user_entity.roles}. A {@code findFirst()} over the
     * unordered authority set pulled sometimes the one, sometimes the other; on 11.08.2026 the
     * same token returned two different tenants 40 minutes apart, which left 28
     * token rows temporarily neither listable nor revocable.
     *
     * <p>{@link McpBearerTokenFilter#TOKEN_MANDAT_PREFIX} comes from exactly one source and
     * therefore occurs exactly once. The fallback to {@code PROPERTY_MANDAT_} serves the transition
     * period: between the root release and the rollout in app/guild/schuetu an older filter may run
     * in the consumer that does not yet set the new authority — without the fallback every token
     * tool would abort there with "Mandant nicht bestimmbar". The fallback inherits the old
     * ambiguity; that is deliberately the lesser harm and ends with the rollout.
     */
    private String mandatBestimmen(Set<String> authorities) {
        return praefixWert(authorities, McpBearerTokenFilter.TOKEN_MANDAT_PREFIX)
                .or(() -> praefixWert(authorities, "PROPERTY_MANDAT_"))
                .orElseThrow(() -> new ZugriffVerweigert("FEHLER: Mandant im Sicherheitskontext nicht bestimmbar."));
    }

    private Optional<String> praefixWert(Set<String> authorities, String praefix) {
        return authorities.stream()
                .filter(a -> a.startsWith(praefix))
                .map(a -> a.substring(praefix.length()))
                .filter(v -> !v.isBlank())
                .findFirst();
    }

    /** Identity of the caller, read from the SecurityContext populated by the bearer filter. */
    private record Aufrufer(Long userId, String mandat, String email) {}

    /** Internal abort carrying a ready-made message that is returned to the MCP client. */
    private static class ZugriffVerweigert extends RuntimeException {
        ZugriffVerweigert(String message) {
            super(message);
        }
    }
}

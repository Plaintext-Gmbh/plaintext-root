/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
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
 * Canonical bearer token filter for MCP/token REST endpoints ({@code /mcp/*} and the like).
 *
 * <p>Centralizes the {@code McpBearerTokenFilter} implementations that were previously copied —
 * and had diverged — in plaintext-app, plaintext-iot and plaintext-schuetu. The endpoints are
 * registered as {@code permitAll} in the Spring Security chain (see
 * {@code plaintext.security.permitAllPatterns}) so that the request gets through to the servlet;
 * the actual authentication is performed by THIS filter. It is registered via
 * {@link org.springframework.boot.web.servlet.FilterRegistrationBean} (see
 * {@link McpBearerTokenFilterConfig}, property-driven), validates the
 * {@code Bearer} token and returns 401 JSON for a missing/invalid token without continuing the
 * filter chain.</p>
 *
 * <p><b>Validation strategies</b> (configurable, see {@link McpBearerTokenFilterProperties}):</p>
 * <ul>
 *   <li>{@link #jwtOnly(JwtTokenService, McpUserRoles)} — pure JWT signature/expiry check without
 *       DB access. Historically the workaround for the Hikari connection leak of
 *       {@link ApiTokenService#validateToken(String)}, which back then was still annotated
 *       {@code @Transactional}.
 *       Trade-off: server-side token revocation only takes effect at the JWT expiry.</li>
 *   <li>{@link #withRevocationCheck(IApiTokenService, McpUserRoles)} — full validation
 *       including a DB revocation check. Since the leak fix in {@link ApiTokenService#validateToken(String)}
 *       (no longer {@code @Transactional}, root ≥ 1.246.0) it can be called leak-free from servlet
 *       filters as well and is therefore the recommended strategy.</li>
 * </ul>
 *
 * <p><b>SecurityContext hygiene:</b> The filter NEVER mutates the existing
 * {@link SecurityContext} object in place. If the request is sent with an HTTP session cookie
 * (e.g. SPAs that poll a token REST API WITH the session cookie of the logged-in user),
 * {@code HttpSessionSecurityContextRepository} holds exactly that context reference — a
 * {@code getContext().setAuthentication(...)} would overwrite the session authentication in place
 * with the reduced token set and rob the user of their real roles. A fresh context is therefore
 * set and the original one restored in the {@code finally}; that way no token authentication is
 * left behind on pooled Tomcat threads either.</p>
 *
 * <p><b>Roles:</b> The MCP JWT deliberately carries NO roles. So that
 * {@code PlaintextSecurity#getAllowedMandate()} recognizes ROOT, the filter loads the real roles
 * of the token user leak-free via {@link McpUserRoles} and stores them as
 * {@code ROLE_*}/{@code PROPERTY_*} authorities.</p>
 *
 * <p><b>Scope:</b> If the token carries a {@code scope} claim ({@code READ}/{@code WRITE}/
 * {@code ADMIN}, see {@link JwtTokenService#generateToken(Long, String, String, String, int, String)}),
 * the filter grants CUMULATIVE {@code SCOPE_*} authorities (ADMIN also gets SCOPE_WRITE +
 * SCOPE_READ, WRITE also SCOPE_READ) — downstream apps then check via {@code @PreAuthorize
 * ("hasAuthority('SCOPE_WRITE')")} or similar, without hasAnyAuthority chains. <b>If the claim is
 * missing, only {@code READ} applies since card 312</b> (fail-closed). Before that {@code ADMIN}
 * applied as a "gentle migration" — since token issuance did not assign any scope back then, every
 * API token was effectively a full-access token. Anyone still running scope-less legacy tokens in
 * production can restore the old behaviour for a limited time via
 * {@code plaintext.mcp.bearer-filter.legacy-scope-admin=true}.</p>
 *
 * <p><b>Revocation:</b> If the token carries a {@code jti} claim AND a {@link JtiRevocationChecker}
 * is available (optional collaborator, see {@link McpBearerTokenFilterConfig}), a request with a
 * revoked token is rejected with 401 just like an invalid token. Without a checker (default) or
 * without a {@code jti} claim (legacy token) no revocation check takes place.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
public class McpBearerTokenFilter implements Filter {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Prefix of the authority that carries the tenant <b>of the presented token</b> (card 670).
     *
     * <p>{@code PROPERTY_MANDAT_*} already exists — but regularly <b>two</b> of them are in the
     * context: one from the token claim and one from the user roles in
     * {@code my_user_entity.roles}, where in PROD the same tenant is stored in upper case
     * ({@code PROPERTY_MANDAT_PLAINTEXT} next to {@code PROPERTY_MANDAT_plaintext}). Whoever pulls
     * "the" tenant out of that set gets sometimes one and sometimes the other, depending on the
     * hash order — measured on 11.08.2026 as two different results for the same token, which left
     * 28 token rows temporarily neither listable nor revocable.
     *
     * <p>This authority comes from exactly one source and therefore occurs exactly once. The
     * existing {@code PROPERTY_MANDAT_*} remain untouched — {@code getAllowedMandate()} and the
     * role check depend on them.
     */
    static final String TOKEN_MANDAT_PREFIX = "PROPERTY_TOKEN_MANDAT_";

    private final TokenValidator tokenValidator;
    private final McpUserRoles mcpUserRoles;
    private final JtiRevocationChecker revocationChecker;

    /**
     * Legacy behaviour for tokens without a {@code scope} claim (card 312, H-7): {@code true} still
     * interprets a missing scope as {@code ADMIN}, {@code false} (default) grants only {@code READ}.
     * Set from {@code plaintext.mcp.bearer-filter.legacy-scope-admin}; as a setter instead of a
     * constructor parameter, so that the existing factories/constructors remain unchanged.
     */
    @Setter
    private boolean legacyScopeAdmin = false;

    /**
     * @param tokenValidator    validation strategy for the raw bearer token (without prefix)
     * @param mcpUserRoles      leak-free role lookup for the token user
     * @param revocationChecker optional jti blocklist check (never {@code null} — no-op default
     *                          via the static factories when no app bean is present)
     */
    public McpBearerTokenFilter(TokenValidator tokenValidator, McpUserRoles mcpUserRoles, JtiRevocationChecker revocationChecker) {
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator");
        this.mcpUserRoles = Objects.requireNonNull(mcpUserRoles, "mcpUserRoles");
        this.revocationChecker = Objects.requireNonNull(revocationChecker, "revocationChecker");
    }

    /**
     * JWT-only validation (signature + expiry, NO DB access, no revocation before expiry) —
     * the previous behaviour of the app/schuetu copies, without a jti blocklist check.
     */
    public static McpBearerTokenFilter jwtOnly(JwtTokenService jwtTokenService, McpUserRoles mcpUserRoles) {
        return jwtOnly(jwtTokenService, mcpUserRoles, jti -> false);
    }

    /** Like {@link #jwtOnly(JwtTokenService, McpUserRoles)}, additionally with a jti blocklist check. */
    public static McpBearerTokenFilter jwtOnly(JwtTokenService jwtTokenService, McpUserRoles mcpUserRoles,
                                                JtiRevocationChecker revocationChecker) {
        Objects.requireNonNull(jwtTokenService, "jwtTokenService");
        return new McpBearerTokenFilter(
                token -> jwtTokenService.validateToken(token)
                        .map(r -> new ValidatedToken(r.userId(), r.mandat(), r.email(), r.scope(), r.jti())),
                mcpUserRoles, revocationChecker);
    }

    /**
     * Full validation including a DB revocation check — the previous behaviour of the iot copy
     * and the recommended strategy.
     * <p>
     * <b>No longer "leak-free since root ≥ 1.246.0" (card 655, 11.08.2026).</b> That fix removed
     * the {@code @Transactional} leak; the view-bound one remains: the OSIV filter wraps this
     * filter chain, which is why the JPA revocation lookup holds its DB connection until the end
     * of the request — with an MCP session therefore for its entire lifetime. Measured in
     * plaintext-iot: 15 {@code Apparent connection leak detected} in 7 days with
     * {@code ApiTokenService.validateVerifiedToken} on the stack. Assessment and the reasoning why
     * this is not (yet) reworked are in the Javadoc of {@link ApiTokenService#validateToken}. Without a jti blocklist check
     * (the hash allowlist in {@link ApiTokenService} already covers revocation here; tokens minted
     * by this strategy currently carry no scope/jti claim).
     */
    public static McpBearerTokenFilter withRevocationCheck(IApiTokenService apiTokenService, McpUserRoles mcpUserRoles) {
        return withRevocationCheck(apiTokenService, mcpUserRoles, jti -> false);
    }

    /** Like {@link #withRevocationCheck(IApiTokenService, McpUserRoles)}, additionally with a jti blocklist check. */
    public static McpBearerTokenFilter withRevocationCheck(IApiTokenService apiTokenService, McpUserRoles mcpUserRoles,
                                                             JtiRevocationChecker revocationChecker) {
        Objects.requireNonNull(apiTokenService, "apiTokenService");
        return new McpBearerTokenFilter(
                token -> apiTokenService.validateToken(token)
                        // scope MUST be passed through (card 349): otherwise every token in this
                        // strategy would arrive without a claim and the fail-closed default would
                        // degrade it to READ — switching to validation: DATABASE would then silently
                        // cut all EINTRAGEN flows (Zeiterfassung-Uhr, Juriwagen) down to read access.
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
        // Unambiguous second copy of the same value — see TOKEN_MANDAT_PREFIX. It is the only
        // tenant authority that is guaranteed to occur exactly once.
        authorities.add(new SimpleGrantedAuthority(TOKEN_MANDAT_PREFIX + validation.mandat()));
        addScopeAuthorities(authorities, validation.scope());
        // Real roles of the user (ROOT/ADMIN/PROPERTY_MANDAT_* etc.) — so that getAllowedMandate() is correct.
        for (String role : mcpUserRoles.rolesForUser(validation.userId())) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String r = role.toUpperCase();
            // PROPERTY_TOKEN_MANDAT_* is reserved and comes EXCLUSIVELY from the token
            // (card 670). If it could also come from the roles column, more than one would be in
            // the context again — and worse: an entry in `my_user_entity.roles` could override the
            // tenant of the token and thereby shift the tenant boundary. It is therefore discarded
            // here instead of being adopted.
            if (r.startsWith(TOKEN_MANDAT_PREFIX)) {
                log.warn("Rolle {} des Benutzers {} ignoriert: {}* ist fuer den Token-Mandanten reserviert",
                        role, validation.userId(), TOKEN_MANDAT_PREFIX);
                continue;
            }
            authorities.add(new SimpleGrantedAuthority(r.startsWith("PROPERTY_") ? r : "ROLE_" + r));
        }
        var auth = new UsernamePasswordAuthenticationToken(validation.email(), null, authorities);

        // IMPORTANT: do NOT mutate the existing SecurityContext object (session context protection,
        // see class Javadoc). Set a fresh context, restore the previous one in the finally —
        // that also defensively clears the token authentication off the pooled thread.
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext bearerContext = SecurityContextHolder.createEmptyContext();
        bearerContext.setAuthentication(auth);
        SecurityContextHolder.setContext(bearerContext);
        try {
            log.debug("MCP request authenticated for user {} mandat={}", validation.userId(), validation.mandat());
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException ex) {
            AccessDeniedException e = findeAccessDenied(ex);
            if (e == null) {
                throw ex;
            }
            // SECURITY/API CONTRACT (card 652): a bearer client lacking a permission used to get
            // HTTP 302 to /login.html — HTML for a device that expects JSON. Measured on
            // schuetu INT on 11.08.2026: READ token against
            // @PreAuthorize("hasAuthority('SCOPE_WRITE')") -> 302; with `curl -L` that turns into
            // HTTP 200 with 14 534 bytes of login page, i.e. a success in the calling script.
            //
            // WHY THE CATCH TYPE IS BROADER THAN THE EXPECTED EXCEPTION (card 652, measured on
            // 11.08.2026): the first attempt caught AccessDeniedException here exclusively — and
            // was never executed. The reason is not the position of this filter, but the
            // WRAPPING: the AuthorizationDeniedException from @PreAuthorize is wrapped by the
            // DispatcherServlet into a jakarta.servlet.ServletException before it reaches the
            // filter chain. An integration test over the real chain made that visible
            // (BearerAccessDeniedChainTest): "filter sees exception
            // jakarta.servlet.ServletException". Spring's own ExceptionTranslationFilter therefore
            // does the same as this method — it searches the cause chain.
            //
            // Without that unwrapping the exception travelled further outwards to the
            // ExceptionTranslationFilter; there the SecurityContext had already been made anonymous
            // again by the finally below, and the LoginUrlAuthenticationEntryPoint turned the
            // denied permission into a redirect to /login.html. A device got HTML instead of JSON,
            // a script with `curl -L` even HTTP 200 — a missing permission reported as success.
            //
            // The case is concluded here, where it arose: the same JSON shape as the 401
            // above, and only for the url-patterns of this filter — JSF/browser paths never come
            // through here and still redirect to /login.html.
            if (httpResponse.isCommitted()) {
                // The response is already under way (e.g. an SSE connection under /mcp): appending a
                // JSON body would destroy the data stream. Better to pass the exception on instead.
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

    /**
     * Searches the cause chain for an {@link AccessDeniedException} (card 652).
     *
     * <p>The exception from {@code @PreAuthorize} does not reach a servlet filter in its own
     * shape, but wrapped in a {@link ServletException}. That is exactly what the first attempt
     * missed. Spring Security's own {@code ExceptionTranslationFilter} solves it the same way,
     * via its {@code ThrowableAnalyzer}.</p>
     *
     * <p>The chain is traversed with a depth limit: an exception that carries itself as its cause
     * (occurs with some wrappers) would otherwise lead to an endless loop.</p>
     *
     * @return the exception found, or {@code null} if none is contained in the chain
     */
    public static AccessDeniedException findeAccessDenied(Throwable ex) {
        Throwable aktuell = ex;
        for (int tiefe = 0; aktuell != null && tiefe < 10; tiefe++) {
            if (aktuell instanceof AccessDeniedException treffer) {
                return treffer;
            }
            Throwable ursache = aktuell.getCause();
            if (ursache == aktuell) {
                return null;
            }
            aktuell = ursache;
        }
        return null;
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Valid Bearer token required\"}");
    }

    /**
     * Counterpart to {@link #unauthorized(HttpServletResponse)} for the case "token valid, permission
     * missing" (card 652). Deliberately without stating which scope was missing: the client cannot
     * change anything about it, and the endpoint permission matrix does not belong in an error response.
     */
    private void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Token lacks the required scope for this endpoint\"}");
    }

    /**
     * Grants cumulative {@code SCOPE_*} authorities: SCOPE_READ always, SCOPE_WRITE <b>and</b>
     * SCOPE_EINTRAGEN additionally for WRITE/EINTRAGEN/ADMIN, SCOPE_ADMIN additionally for ADMIN. An
     * unrecognized claim value gets ONLY SCOPE_READ (least privilege).
     *
     * <p><b>Transition window (card 545):</b> {@code WRITE} is the new name of the previous scope
     * {@code EINTRAGEN} (decision by Daniel, 05.08.2026). Both claim values are valid and grant
     * the <em>same</em> authorities — otherwise one half of the code would check against
     * SCOPE_EINTRAGEN and the other against SCOPE_WRITE, and a token would only make it half the
     * way. The old name is only dropped once all downstream {@code @PreAuthorize} expressions use
     * {@code SCOPE_WRITE} (stage 3 of the card).</p>
     *
     * <p><b>Missing/empty claim ⇒ {@code READ} (card 312, H-7).</b> Previously {@code ADMIN} applied
     * here as a "gentle migration" — combined with the fact that token issuance did not assign any
     * scope at all, that made <em>every</em> API token a full-access token in practice. Anyone who
     * needs the legacy behaviour for a limited time sets
     * {@code plaintext.mcp.bearer-filter.legacy-scope-admin=true}
     * (see {@link McpBearerTokenFilterProperties#isLegacyScopeAdmin()}).</p>
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
     * Validation strategy: raw bearer token (without the {@code "Bearer "} prefix) in,
     * validated principal out — or {@link Optional#empty()} for an invalid token.
     */
    @FunctionalInterface
    public interface TokenValidator {
        Optional<ValidatedToken> validate(String token);
    }

    /**
     * Minimal validated principal that the filter needs for authentication + authorities.
     *
     * @param userId user ID from the token
     * @param mandat mandat identifier from the token
     * @param email  e-mail of the token user (becomes the principal name)
     * @param scope  {@code READ}/{@code EINTRAGEN}/{@code ADMIN}, or {@code null} (legacy token
     *               without a scope claim; then the fail-closed default applies)
     * @param jti    token ID for the revocation check, or {@code null} (legacy token, or the
     *               DATABASE strategy, which uses its own hash-based revocation)
     */
    public record ValidatedToken(Long userId, String mandat, String email, String scope, String jti) {}
}

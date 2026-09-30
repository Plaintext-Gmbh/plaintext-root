/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Caps the scope of an API token by the roles of its owner (cards 1363/1365, audit part D, HD7).
 *
 * <p><b>Why:</b> the scope of a token used to be chosen freely by the user on {@code api-token.html}
 * (READ/WRITE/ADMIN for every role USER), and the {@link McpBearerTokenFilter} granted the
 * {@code SCOPE_*} authorities from the claim alone. Many MCP tools and REST endpoints of the apps
 * check only the scope. So the scope replaced the role: a helper with nothing but a subject role
 * issued himself an ADMIN token and called tools the UI never showed him.</p>
 *
 * <p><b>Rule (default):</b> {@code WRITE} and {@code ADMIN} only for the roles ADMIN or ROOT,
 * everybody else at most {@code READ}. It applies twice:</p>
 * <ul>
 *   <li><b>when issuing</b> — the UI offers only the permitted levels and rejects more;</li>
 *   <li><b>in the filter</b> — effective scope = min(claim, what the <em>current</em> roles allow).
 *       Tokens already issued are capped on their next request, and a role withdrawn later takes
 *       effect immediately, without revoking the token.</li>
 * </ul>
 *
 * <p><b>Per app (prefix {@code plaintext.apitoken.scope-deckel}):</b></p>
 * <pre>{@code
 * plaintext:
 *   apitoken:
 *     scope-deckel:
 *       enabled: true                  # false = old behaviour (claim alone), only as emergency switch
 *       lese-rollen: []                # empty = every user may use a READ token;
 *                                      # otherwise tokens only for these roles (others: 403)
 *       schreib-rollen: [ADMIN, ROOT]  # may hold WRITE
 *       admin-rollen: [ADMIN, ROOT]    # may hold ADMIN
 *       service-token-schreib-rollen:  # token NAME -> subject roles that keep WRITE for
 *         schuetu-turnier-ui: [EINTRAGEN, KONTROLLIERER]   # this server-minted token (never ADMIN)
 * }</pre>
 *
 * <p><b>Service tokens:</b> an app may mint tokens server-side for subject roles (schuetu: the
 * turnier-ui for referees and controllers, {@code ApiTokenService#createServiceToken}). Such a
 * token keeps {@code WRITE} only if its name is listed here AND the owner holds one of the listed
 * roles; the app itself then has to check the role per action. A user cannot obtain such a
 * {@code WRITE} token through the UI under the same name: the UI caps the claim at issuance, and
 * min(READ, WRITE) stays READ.</p>
 *
 * <p><b>Role names:</b> root grants {@code "ROLE_" + role.toUpperCase()} (session, OIDC, filter).
 * The configured and the presented role names are therefore compared without the prefix and
 * case-insensitively — {@code admin}, {@code ADMIN} and {@code ROLE_ADMIN} are the same role.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
@ConfigurationProperties(prefix = "plaintext.apitoken.scope-deckel")
@Getter
@Setter
public class ApiTokenScopeDeckel {

    /** Scope levels in ascending order. {@code EINTRAGEN} is the legacy name of WRITE (card 545). */
    public static final String READ = "READ";
    public static final String WRITE = "WRITE";
    public static final String ADMIN = "ADMIN";

    private static final List<String> STUFEN = List.of(READ, WRITE, ADMIN);

    /** {@code false} switches the cap off (claim alone, behaviour before card 1363). */
    private boolean enabled = true;

    /** Roles that may use an API token at all. Empty = every authenticated user (READ). */
    private List<String> leseRollen = new ArrayList<>();

    /** Roles that may hold {@code WRITE}. */
    private List<String> schreibRollen = new ArrayList<>(List.of("ADMIN", "ROOT"));

    /** Roles that may hold {@code ADMIN}. */
    private List<String> adminRollen = new ArrayList<>(List.of("ADMIN", "ROOT"));

    /** Token name → roles that keep {@code WRITE} for a server-minted token of that name. */
    private Map<String, List<String>> serviceTokenSchreibRollen = new LinkedHashMap<>();

    /**
     * Highest scope the given roles allow for a token with this name.
     *
     * @param rollen    roles of the token owner, raw ({@code admin}) or as authority
     *                  ({@code ROLE_ADMIN}); {@code PROPERTY_*} entries are ignored
     * @param tokenName name of the token, may be {@code null}
     * @return {@code READ}/{@code WRITE}/{@code ADMIN}, or empty if the owner may not use a token at all
     */
    public Optional<String> hoechsterScope(Collection<String> rollen, String tokenName) {
        if (!enabled) {
            return Optional.of(ADMIN);
        }
        Set<String> eigene = normalisiere(rollen);
        if (trifft(eigene, adminRollen)) {
            return Optional.of(ADMIN);
        }
        if (trifft(eigene, schreibRollen)) {
            return Optional.of(WRITE);
        }
        if (tokenName != null) {
            List<String> dienst = serviceTokenSchreibRollen.get(tokenName.trim());
            if (dienst != null && trifft(eigene, dienst)) {
                return Optional.of(WRITE);
            }
        }
        if (!leseRollen.isEmpty() && !trifft(eigene, leseRollen)) {
            return Optional.empty();
        }
        return Optional.of(READ);
    }

    /**
     * Caps a scope claim: min(claim, {@link #hoechsterScope}).
     *
     * @param scope     the claim ({@code READ}/{@code WRITE}/{@code EINTRAGEN}/{@code ADMIN}); an
     *                  unknown value counts as {@code READ}, as in the filter
     * @return the capped scope, or empty if the owner may not use a token at all
     */
    public Optional<String> deckeln(String scope, Collection<String> rollen, String tokenName) {
        Optional<String> hoechster = hoechsterScope(rollen, tokenName);
        if (hoechster.isEmpty()) {
            return Optional.empty();
        }
        int verlangt = stufe(scope);
        int erlaubt = stufe(hoechster.get());
        return Optional.of(STUFEN.get(Math.min(verlangt, erlaubt)));
    }

    /**
     * Levels a user may choose in the UI (ascending). Service-token exceptions do not count here:
     * they only apply to server-minted tokens. Empty = no token at all.
     */
    public List<String> waehlbareScopes(Collection<String> rollen) {
        Optional<String> hoechster = hoechsterScope(rollen, null);
        if (hoechster.isEmpty()) {
            return List.of();
        }
        return STUFEN.subList(0, stufe(hoechster.get()) + 1);
    }

    /** Position of a scope on the ladder; {@code EINTRAGEN} = WRITE, unknown/empty = READ. */
    static int stufe(String scope) {
        if (scope == null) {
            return 0;
        }
        String s = scope.trim().toUpperCase(Locale.ROOT);
        if (s.equals("EINTRAGEN")) {
            return 1;
        }
        int i = STUFEN.indexOf(s);
        return Math.max(i, 0);
    }

    /** Role name without {@code ROLE_} prefix, upper case; {@code null} for blank values. */
    static String normalisiereRolle(String rolle) {
        if (rolle == null || rolle.isBlank()) {
            return null;
        }
        String r = rolle.trim().toUpperCase(Locale.ROOT);
        return r.startsWith("ROLE_") ? r.substring("ROLE_".length()) : r;
    }

    private static Set<String> normalisiere(Collection<String> rollen) {
        if (rollen == null) {
            return Set.of();
        }
        return rollen.stream()
                .map(ApiTokenScopeDeckel::normalisiereRolle)
                .filter(r -> r != null && !r.startsWith("PROPERTY_"))
                .collect(Collectors.toSet());
    }

    private static boolean trifft(Set<String> eigene, Collection<String> konfiguriert) {
        if (konfiguriert == null) {
            return false;
        }
        return konfiguriert.stream()
                .map(ApiTokenScopeDeckel::normalisiereRolle)
                .anyMatch(r -> r != null && eigene.contains(r));
    }
}

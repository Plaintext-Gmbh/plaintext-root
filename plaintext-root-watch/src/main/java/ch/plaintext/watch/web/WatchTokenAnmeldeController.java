/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.McpUserRoles;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import ch.plaintext.watch.service.WatchHandyLinkService;
import ch.plaintext.watch.service.WatchStateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Entry point of the personal phone link (card 1257): checks the token in the URL and, if it is
 * good, opens the watch — without a sign-in.
 *
 * <h2>Why the token is exchanged for a session instead of travelling along</h2>
 *
 * <p>A token in a query string ends up in the access log of every hop, in the {@code Referer} of
 * every outgoing link and in the browser history. Carrying it on every request would multiply
 * that by the number of requests. It is therefore read <b>once</b>, put into the server-side
 * session, and the browser is redirected to an address without it — a redirect replaces the
 * history entry, so the address with the token does not even stay in the back button.</p>
 *
 * <p>What that does <em>not</em> soften: the session is not a normal sign-in. It is bound to the
 * token, and {@code WatchTokenSitzungFilter} checks on <b>every</b> request that the token is
 * still valid in the database and that the request is going to a watch page. Revoking the token
 * therefore ends a session that is already open, on its next click — not when it times out.</p>
 *
 * <h2>Three locks, not one</h2>
 *
 * <ol>
 *   <li><b>Signature and revocation</b> — {@link IApiTokenService#validateToken} checks the RSA
 *       signature, the expiry <em>and</em> the row in {@code api_token}. A revoked token fails
 *       here immediately; signature-only validation would keep it alive for its full term.</li>
 *   <li><b>The name</b> — only a token named {@link WatchHandyLinkService#TOKEN_NAME} opens the
 *       watch. Any other token of the same user, including an ADMIN API token, is refused. The
 *       same prefix makes this token worthless at the API
 *       ({@link IApiTokenService#UI_TOKEN_NAME_PREFIX}).</li>
 *   <li><b>The user's own switch</b> — {@code handyLinkAktiv}. Switching the link off in the
 *       settings works even if a revocation somehow did not.</li>
 * </ol>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class WatchTokenAnmeldeController {

    /**
     * The one authority that marks a session as token-bound. Everything else this session is
     * allowed to do is decided by the user's real roles; <em>where</em> it may go is decided by
     * this marker and {@code WatchTokenSitzungFilter}.
     */
    public static final String ROLLE_TOKEN_SITZUNG = "ROLE_WATCH_TOKEN";

    /** Session attribute holding the raw token, for the per-request revocation check. */
    public static final String SITZUNG_TOKEN = "watch.handylink.token";

    /**
     * Where the link lands after the token has been taken out of the address.
     *
     * <p>Not a page but {@link WatchStartController#PFAD} (card 1289). While this was the
     * literal {@code /watch/home.html}, every tap on the phone link landed on the overview, no
     * matter which page the owner had been on the evening before — the remembered position was
     * written faithfully and then never read at start.</p>
     *
     * <p>Resolving the page <em>here</em> was tried and does not work: the security context of
     * this request is put into the session a few lines below, the current thread has none, and
     * {@link WatchStateService} reads the user through {@code PlaintextSecurityHolder}. The
     * redirect makes the question go away — the follow-up request is an ordinary one with the
     * context restored from the session.</p>
     */
    static final String ZIEL = WatchStartController.PFAD;

    private final ObjectProvider<IApiTokenService> tokenDienst;
    private final ObjectProvider<McpUserRoles> benutzerRollen;
    private final PlaintextSecurity sicherheit;
    private final WatchStateService zustand;

    /**
     * {@code GET /nosec/watch?t=<jwt>}.
     *
     * <p>Under {@code /nosec/} because it has to work without a sign-in — that is the whole
     * point of the link. The strict rate-limit bucket of {@code RateLimitFilter} keys on exactly
     * this path, so the token cannot be guessed at speed.</p>
     *
     * <p>Every rejection answers the same way: {@code 403} with a plain sentence. Deliberately
     * no distinction between "unknown", "revoked", "wrong kind of token" and "switched off" —
     * the difference would tell a caller holding a random token which of the four he is closest
     * to.</p>
     */
    @GetMapping(WatchHandyLinkService.EINSTIEGSPFAD)
    public void einstieg(@RequestParam(name = "t", required = false) String token,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<Angemeldet> geprueft = pruefe(token);
        if (geprueft.isEmpty()) {
            // Kein Log des Tokens, auch nicht gekuerzt. Was hilft, ist die Adresse des Anrufers,
            // und die steht im Zugriffslog ohnehin.
            log.info("Watch-Handy-Link abgewiesen (ungueltig, widerrufen, falscher Tokentyp oder abgeschaltet)");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Dieser Link gilt nicht mehr.");
            return;
        }
        Angemeldet a = geprueft.get();

        // Sitzungsfixierung: eine mitgebrachte Sitzung wird verworfen, bevor die neue Kennung
        // gesetzt wird. Sonst koennte jemand dem Opfer vorher ein Sitzungscookie unterschieben
        // und uebernaehme mit dessen Klick die angemeldete Sitzung.
        HttpSession alt = request.getSession(false);
        if (alt != null) {
            alt.invalidate();
        }
        HttpSession neu = request.getSession(true);

        SecurityContext kontext = SecurityContextHolder.createEmptyContext();
        kontext.setAuthentication(new UsernamePasswordAuthenticationToken(a.benutzer(), null, a.rechte()));
        neu.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, kontext);
        neu.setAttribute(SITZUNG_TOKEN, a.token());

        log.info("Watch-Handy-Link angenommen (userId={}, mandat={})", a.userId(), a.mandat());
        // Weiterleitung statt Weiterreichen: nur so verschwindet der Token aus der Adresszeile,
        // aus dem Verlauf und aus dem Referrer der Folgeaufrufe.
        response.sendRedirect(request.getContextPath() + ZIEL);
    }

    /** The three locks, in order. Empty means: not letting this through. */
    private Optional<Angemeldet> pruefe(String token) {
        IApiTokenService dienst = tokenDienst.getIfAvailable();
        if (dienst == null || token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<ApiTokenValidationResult> ergebnis;
        try {
            ergebnis = dienst.validateToken(token);
        } catch (RuntimeException e) {
            // Fail-CLOSED, anders als beim jti-Negativcache im MCP-Filter: dort haengt eine
            // laufende Maschinenstrecke daran, hier nur die Bequemlichkeit eines Links. Wer im
            // Zweifel durchliesse, liesse im Zweifel einen widerrufenen Dauerausweis durch.
            log.warn("Watch-Handy-Link: Pruefung nicht moeglich, weise ab: {}", e.toString());
            return Optional.empty();
        }
        if (ergebnis.isEmpty() || ergebnis.get().userId() == null) {
            return Optional.empty();
        }
        ApiTokenValidationResult r = ergebnis.get();
        if (!WatchHandyLinkService.TOKEN_NAME.equals(r.tokenName())) {
            return Optional.empty();
        }
        String benutzer = sicherheit.getUsernameForUser(r.userId());
        if (benutzer == null || benutzer.isBlank() || !zustand.handyLinkAktivFuer(benutzer)) {
            return Optional.empty();
        }
        return Optional.of(new Angemeldet(benutzer, r.userId(), r.mandat(), token, rechte(r)));
    }

    /**
     * The authorities of a token session.
     *
     * <h2>Why the user's real roles are in here — and why that is not "the full session"</h2>
     *
     * <p>Which watch pages exist for a user is decided by the modules themselves, and they ask
     * the page guard for their menu roles ({@code ZeiterfassungWatchPage.available()} and its
     * five siblings). A session without those roles would show an empty watch, so the roles have
     * to be here.</p>
     *
     * <p>What keeps this from being the owner's whole session is not the role set but the path:
     * {@code WatchTokenSitzungFilter} answers 403 for everything that is not a watch page. The
     * roles decide <em>what is visible</em>, the confinement decides <em>where the session may
     * go</em>. Trying to do both with the role set was tried first and does not work — it makes
     * the watch blank.</p>
     */
    private Set<GrantedAuthority> rechte(ApiTokenValidationResult r) {
        Set<GrantedAuthority> rechte = new LinkedHashSet<>();
        rechte.add(new SimpleGrantedAuthority(ROLLE_TOKEN_SITZUNG));
        // Dieselbe Schreibweise wie im MCP-Bearer-Filter: PlaintextSecurityImpl.getId() und
        // getMandat() lesen Benutzer und Mandant aus genau diesen Autoritaeten heraus.
        rechte.add(new SimpleGrantedAuthority("PROPERTY_MYUSERID_" + r.userId()));
        rechte.add(new SimpleGrantedAuthority("PROPERTY_MANDAT_" + r.mandat()));
        McpUserRoles rollen = benutzerRollen.getIfAvailable();
        if (rollen == null) {
            return rechte;
        }
        for (String rolle : rollen.rolesForUser(r.userId())) {
            if (rolle == null || rolle.isBlank()) {
                continue;
            }
            String gross = rolle.toUpperCase(java.util.Locale.ROOT);
            rechte.add(new SimpleGrantedAuthority(gross.startsWith("PROPERTY_") ? gross : "ROLE_" + gross));
        }
        return rechte;
    }

    /** What a successful check produced. */
    private record Angemeldet(String benutzer, Long userId, String mandat, String token,
                              Set<GrantedAuthority> rechte) {
    }
}

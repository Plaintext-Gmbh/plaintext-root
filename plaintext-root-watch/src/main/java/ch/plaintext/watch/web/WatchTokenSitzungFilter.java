/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.apitoken.IApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Keeps a token session inside the watch — and ends it the moment the token is revoked
 * (card 1257).
 *
 * <h2>Why this filter has to exist</h2>
 *
 * <p>A session opened by {@code WatchTokenAnmeldeController} carries the owner's real roles,
 * because the watch pages ask the page guard for them and would otherwise be empty. Without a
 * second gate that session would be the owner's whole session: {@code anyRequest()
 * .authenticated()} lets it to every page of the application. A link handed to someone "just
 * for the watch" would then be a login.</p>
 *
 * <p>The gate is by <b>path and deny by default</b>: anything not on the short allow-list is
 * answered 403. A new page somewhere in the application is therefore out of reach the day it is
 * written, without anybody having to remember this filter.</p>
 *
 * <h2>Why the token is checked on every request</h2>
 *
 * <p>"Revocation takes effect immediately" is only true if something looks. Checking once, at
 * the exchange, would leave an open session alive until it times out — the user would press
 * "generate a new link", be told the old one is dead, and the old phone would keep working.
 * So {@link IApiTokenService#validateToken} runs per request; it is one indexed read on
 * {@code api_token}, against a page that a single person taps.</p>
 *
 * <h2>Order</h2>
 *
 * <p>Registered at {@code -99} — one behind the {@code springSecurityFilterChain} at
 * {@code -100}, therefore <b>inside</b> it: by the time this runs, Spring Security has loaded
 * the session context into the {@code SecurityContextHolder} and its own authorization has
 * already passed. This filter only ever takes away, never grants; it cannot open anything
 * Spring Security has closed.</p>
 *
 * <p>Only on {@code DispatcherType.REQUEST}. The {@code ERROR} pass is the container's own and
 * carries no caller (card 652); intercepting it would turn a 404 into a 403.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@RequiredArgsConstructor
@Slf4j
public class WatchTokenSitzungFilter extends OncePerRequestFilter {

    /**
     * Everything a token session may reach.
     *
     * <p>{@code /watch/} is the pages themselves, including their postbacks. The two resource
     * prefixes are the JSF static pipeline (the stylesheet of the watch template); they hand out
     * files from the classpath and are {@code permitAll} anyway. {@code /nosec/} is public in
     * any case, and the entry point itself lies there.</p>
     *
     * <p>Not on the list, on purpose: {@code /watch-einstellungen.html}. The settings page issues
     * and revokes the link. A link that could revoke itself — or issue a new one — would be its
     * own administration.</p>
     */
    static final List<String> ERLAUBT = List.of(
            "/watch/",
            "/jakarta.faces.resource/",
            "/javax.faces.resource/",
            "/nosec/");

    private final ObjectProvider<IApiTokenService> tokenDienst;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!istTokenSitzung()) {
            chain.doFilter(request, response);
            return;
        }

        String pfad = pfadOhneKontext(request);
        if (!erlaubt(pfad)) {
            log.info("Watch-Token-Sitzung abgewiesen auf {} — nur Watch-Seiten sind erlaubt", pfad);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Nur die Watch-Seiten");
            return;
        }

        if (!tokenNochGueltig(request)) {
            HttpSession sitzung = request.getSession(false);
            if (sitzung != null) {
                sitzung.invalidate();
            }
            SecurityContextHolder.clearContext();
            log.info("Watch-Token-Sitzung beendet: der Token ist nicht mehr gueltig");
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Dieser Link gilt nicht mehr.");
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Der Einstieg selbst legt die Sitzung erst an; ihn zu pruefen hiesse, ihn gegen einen
        // Zustand zu pruefen, den es noch nicht gibt.
        return pfadOhneKontext(request).equals(
                ch.plaintext.watch.service.WatchHandyLinkService.EINSTIEGSPFAD);
    }

    /** Whether the current caller got in through a phone link. */
    private static boolean istTokenSitzung() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority g : auth.getAuthorities()) {
            if (WatchTokenAnmeldeController.ROLLE_TOKEN_SITZUNG.equals(g.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static boolean erlaubt(String pfad) {
        return ERLAUBT.stream().anyMatch(pfad::startsWith);
    }

    /**
     * Re-checks the token of this session against the database.
     *
     * <p>Fail-CLOSED: a session whose token cannot be read is ended. That is the opposite of the
     * MCP filter's negative cache, and on purpose — there a machine flow hangs on the answer,
     * here only the convenience of a link. Nobody is cut off from their data; they sign in.</p>
     */
    private boolean tokenNochGueltig(HttpServletRequest request) {
        HttpSession sitzung = request.getSession(false);
        Object token = sitzung == null ? null : sitzung.getAttribute(WatchTokenAnmeldeController.SITZUNG_TOKEN);
        if (!(token instanceof String jwt) || jwt.isBlank()) {
            return false;
        }
        IApiTokenService dienst = tokenDienst.getIfAvailable();
        if (dienst == null) {
            return false;
        }
        try {
            return dienst.validateToken(jwt)
                    .filter(r -> ch.plaintext.watch.service.WatchHandyLinkService.TOKEN_NAME
                            .equals(r.tokenName()))
                    .isPresent();
        } catch (RuntimeException e) {
            log.warn("Watch-Token-Sitzung: Pruefung nicht moeglich, beende sie: {}", e.toString());
            return false;
        }
    }

    private static String pfadOhneKontext(HttpServletRequest request) {
        String pfad = request.getRequestURI();
        String kontext = request.getContextPath();
        if (kontext != null && !kontext.isEmpty() && pfad.startsWith(kontext)) {
            pfad = pfad.substring(kontext.length());
        }
        return pfad;
    }
}

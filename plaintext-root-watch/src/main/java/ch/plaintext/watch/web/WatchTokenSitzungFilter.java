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
 * <h2>How the refusal answers, and that there is a way out (card 1305)</h2>
 *
 * <p>The refusal itself is unchanged — same status, same paths, nothing opened. What changed is
 * what arrives on the phone. {@code sendError} handed the answer to the container, which ends in
 * Spring Boot's whitelabel page; the owner opened his own start page and got a white screen with
 * no explanation and no way on. {@link WatchSperrSeite} writes the answer instead: what this
 * session is, the way back to the watch, and a button that ends the session.</p>
 *
 * <p>That button is the second half. {@code /logout} is on {@link #ERLAUBT_GENAU} — ending a
 * session is not administering a link (the reasoning is at the constant). Before this card a
 * token session could only be left by deleting the site data in the browser, which is knowledge
 * nobody outside this file had.</p>
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
 * <p>On {@code DispatcherType.REQUEST} <b>and {@code FORWARD}</b>, never {@code ERROR}. The
 * {@code ERROR} pass is the container's own and carries no caller (card 652); intercepting it
 * would turn a 404 into a 403. {@code FORWARD} is not a nicety: every page is addressed as
 * {@code .html} and reaches its view through the forward of
 * {@code UrlRewriteConfig.HtmlToXhtmlRewriteFilter}, which sits far ahead of this filter. While
 * {@code FORWARD} was missing, this confinement bit on no page at all — see
 * {@code WatchTokenFilterConfig} for the measurement (card 1280).</p>
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

    /**
     * The way out — matched whole, not as a prefix (card 1305).
     *
     * <h2>Why signing out is not "administration"</h2>
     *
     * <p>The list above keeps this session out of {@code /watch-einstellungen.html} with the
     * argument that a link must not administer itself. {@code /logout} looked like the same
     * thing and was therefore not on any list. It is not the same thing: administering means
     * changing what the link is worth for <em>every</em> device — issuing, revoking, switching
     * off. Signing out changes nothing about the link at all. It ends this one session on this
     * one phone, and the link opens the watch again on the next tap. A session that cannot be
     * left is not a barrier, it is a trap: until this card the only way out of a token session
     * was to delete the site data in the browser.</p>
     *
     * <h2>What the entry does and does not do</h2>
     *
     * <p>Measured, and the measurement says the entry is <b>not</b> what makes the button work:
     * with this list emptied,
     * {@code WatchHandyLinkPlaywrightIT#abmeldenBeendetDieSitzungOhneDenLinkZuWiderrufen} stays
     * green (20.09.2026, card 1305). A {@code POST /logout} never reaches this filter — Spring
     * Security's {@code LogoutFilter} sits inside the security chain at {@code -100} and answers
     * there, while this filter runs at {@code -99}, that is behind the whole chain. Signing out
     * was therefore already possible before this card; what was missing was a page that offers
     * it.</p>
     *
     * <p>The entry stays nonetheless, and the reason is not belt and braces. It catches every
     * other dispatch onto the path (a {@code GET}, a forward), it survives somebody moving this
     * filter inside the chain, and above all it puts the decision where the confinement is
     * defined: whoever reads this list to find out whether this session can be left now finds
     * the answer in it instead of in the ordering of two filters.
     * {@code WatchTokenSitzungFilterTest#logoutErlaubt} goes red without it and holds the
     * statement in place.</p>
     *
     * <p>Whole-path match on purpose: as a prefix, {@code /logout} would also cover a future
     * {@code /logout-everything.html}. An exit is one address, not a family of them.</p>
     */
    static final List<String> ERLAUBT_GENAU = List.of("/logout");

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
            // Karte 1305: kein sendError. Das erzeugte den zweiten, containereigenen Durchgang
            // auf /error und damit die Whitelabel-Seite — auf dem Telefon des Besitzers eine
            // weisse Seite ohne ein Wort der Erklaerung und ohne Ausgang. Begruendung der
            // Wahl im Klassenkommentar von WatchSperrSeite.
            WatchSperrSeite.nurDieUhr(request, response);
            return;
        }

        if (!tokenNochGueltig(request)) {
            HttpSession sitzung = request.getSession(false);
            if (sitzung != null) {
                sitzung.invalidate();
            }
            SecurityContextHolder.clearContext();
            log.info("Watch-Token-Sitzung beendet: der Token ist nicht mehr gueltig");
            WatchSperrSeite.linkGiltNichtMehr(request, response);
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
        return ERLAUBT_GENAU.contains(pfad) || ERLAUBT.stream().anyMatch(pfad::startsWith);
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

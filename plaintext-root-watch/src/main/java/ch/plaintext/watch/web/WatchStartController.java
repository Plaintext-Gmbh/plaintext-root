/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.service.WatchStateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

/**
 * The entry address of the watch: shows nothing, sends the caller to the page they were last
 * on (card 1289).
 *
 * <p>Daniel, 19.09.2026: „zustand was offen ist beim navigieren soll gespeichert werden beim
 * naechsten aufruf wieder das."</p>
 *
 * <h2>Why a second address was needed at all</h2>
 *
 * <p>The remembering had been working since card 1245: {@code WatchFrameBean.wechsle} stores
 * the page on every move, {@code watch_user_state.aktuelle_seite} keeps it per user in the
 * database, and it survives closing the app. What did not work was the <b>start</b>. Both ways
 * into the watch named a concrete page:</p>
 *
 * <ul>
 *   <li>the phone link, because {@code WatchTokenAnmeldeController.ZIEL} was the literal
 *       {@code /watch/home.html};</li>
 *   <li>the icon on the home screen, because {@code WatchSettingsBean.getWatchAdresse()}
 *       handed out the same literal for people to save.</li>
 * </ul>
 *
 * <p>And a named page wins over the remembered one, on purpose — that is what makes a
 * bookmarked or typed address show what it says. The remembered page only ever decided when a
 * watch page was opened <em>without</em> naming itself, and nobody went that way. So both ways
 * in now name <b>this</b> address, which names no page.</p>
 *
 * <h2>Why not simply redirect {@code home} to the remembered page</h2>
 *
 * <p>Because {@code home} is a real page with tiles, not a doormat. Redirecting it away would
 * make it unreachable for anyone who has ever paged on, and it is the first page of the
 * rotation. A separate address costs one more entry in the routing table and leaves every
 * existing address meaning what it meant.</p>
 *
 * <h2>Why the token entry point cannot do this itself</h2>
 *
 * <p>{@code WatchTokenAnmeldeController} builds the security context and puts it into the
 * <em>session</em>; the thread it runs on still has none, and {@link WatchStateService} reads
 * the user through {@code PlaintextSecurityHolder}. Reading the remembered page there answers
 * for nobody — the same shape of fault as card 1271. The redirect removes the problem instead
 * of working around it: the request that arrives here is an ordinary one, and Spring Security
 * has restored the context from the session before any of this runs.</p>
 *
 * <h2>Why the address carries no extension</h2>
 *
 * <p>{@code joinfaces.faces-servlet.url-mappings} contains {@code *.html}, and
 * {@code UrlRewriteConfig.HtmlToXhtmlRewriteFilter} forwards every {@code .html} to the
 * {@code .xhtml} of the same name. A controller mapped to {@code /watch/start.html} would
 * therefore never be reached — the forward would look for a Facelet that does not exist.
 * Without an extension the request goes to the {@code DispatcherServlet}, and the page guard
 * (which only inspects view extensions) rightly ignores it: this is not a view.</p>
 *
 * <p>It is inside {@code /watch/}, so the confinement of a token session
 * ({@code WatchTokenSitzungFilter.ERLAUBT}) lets it through and re-checks the token on it like
 * on every other watch address. For a signed-out caller it falls under
 * {@code anyRequest().authenticated()} and leads to the login — after which Spring Security's
 * saved request brings the caller back here, and from here to their page.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class WatchStartController {

    /**
     * The entry address. Public because two places hand it out: the phone link
     * ({@code WatchTokenAnmeldeController}) and the address shown for the home screen
     * ({@code WatchSettingsBean}). Whoever changes it here changes both, which is the point.
     */
    public static final String PFAD = "/watch/start";

    /**
     * Where to go when the registry offers nothing at all — every page switched off or barred
     * by role. {@code home} is always present in this module and renders the empty frame, which
     * is the same answer the watch gave before this class existed. Deliberately not a 404: the
     * user asked for their watch, and "empty" is a truthful answer where "not found" would not
     * be.
     */
    static final String RUECKFALL = "/watch/home.html";

    private final WatchPageRegistry registry;
    private final WatchStateService zustand;

    /**
     * {@code GET /watch/start} — one redirect, no body.
     *
     * <p>The target is taken from the page registry and never from the request: there is no
     * parameter here a caller could steer, so this cannot become an open redirect, and a
     * remembered id naming a page that has since been switched off falls back to the first
     * visible one instead of bouncing the user off a page the frame would redirect away from
     * anyway.</p>
     */
    @GetMapping(PFAD)
    public void start(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String ziel = registry.einstieg(zustand.gemerkteSeitenId().orElse(null))
                .map(WatchPage::view)
                .map(view -> view.replace(".xhtml", ".html"))
                .orElse(RUECKFALL);
        log.debug("Watch-Start leitet auf {}", ziel);
        response.sendRedirect(request.getContextPath() + ziel);
    }
}

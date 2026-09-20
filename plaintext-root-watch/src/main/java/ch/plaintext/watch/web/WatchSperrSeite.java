/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * The answer a token session gets when it runs into its own confinement (card 1305).
 *
 * <h2>What was wrong with {@code sendError}</h2>
 *
 * <p>{@code WatchTokenSitzungFilter} used to answer with
 * {@code response.sendError(403, "Nur die Watch-Seiten")}. The sentence went into the log and
 * the container produced its own second pass on {@code /error}, which ends in Spring Boot's
 * whitelabel page: a white screen with three lines of English and no way on. The owner of the
 * application opened his own start page on his phone and got exactly that
 * (Daniel, 20.09.2026 — "app.plaintext.ch hat whitelabel error"). The confinement was right,
 * its answer was not.</p>
 *
 * <h2>Why the page is written here and not delivered some other way</h2>
 *
 * <p>Three ways were available in this house, and the other two were measured against this
 * case first:</p>
 *
 * <ul>
 *   <li><b>Redirect to a readable page</b>, the way {@code PageAccessGuardFilter} sends a
 *       plain GET to {@code /access-denied.html}. That is the closest relative, and it was
 *       rejected for one reason: it turns the 403 into a 302. Card 1280 had just found that
 *       this confinement bit on no page at all; the sharpest assertion that came out of it is
 *       "a token session gets 403 on {@code /index.html}". Trading that status for a redirect
 *       would soften exactly the guarantee that was missing this morning — and
 *       {@code access-denied.xhtml} carries the full page frame, whose "back to the start
 *       page" button leads a token session straight into the next 403.</li>
 *   <li><b>An {@code ErrorViewResolver}</b> next to {@code PlaintextErrorViewResolver}
 *       (card 406). That hook only fires on the container's {@code ERROR} dispatch — the one
 *       pass this filter deliberately stays out of (card 652, where a 403 on that dispatch
 *       once turned into a 302 to the login). Producing the explanation there would mean
 *       building the answer on the dispatch whose brittleness is the reason the filter avoids
 *       it.</li>
 *   <li><b>Writing the answer directly</b>, which is what {@code MaintenanceModeFilter} (503)
 *       and {@code RateLimitFilter.rejectLogin} (429) do — both filters that have to deny with
 *       an explanation. Status stays what it is, no second pass, and the CSRF token needed for
 *       the way out is in the request right here. That is this class.</li>
 * </ul>
 *
 * <h2>Why HTML is not always the answer</h2>
 *
 * <p>The confinement also catches single resources — {@code /plaintext-layout/js/config.js}
 * was in the log of 20.09.2026, pulled by a half-loaded page. Handing an HTML page to a
 * {@code &lt;script src&gt;} helps nobody, so the page is rendered only for callers who say
 * they take {@code text/html}; everyone else gets the same sentence as plain text. Same split
 * as {@code RateLimitFilter}, which answers JSON on the API paths and a page on the login.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
final class WatchSperrSeite {

    /** What the confinement says — in the page, in the plain-text answer and in the log. */
    static final String SATZ_NUR_DIE_UHR = "Nur die Watch-Seiten";

    /**
     * What a dead link says. Word for word the sentence of
     * {@code WatchTokenAnmeldeController}: whoever taps on twice must not be able to tell from
     * the wording whether the session died in the meantime or the link was never good.
     */
    static final String SATZ_LINK_TOT = "Dieser Link gilt nicht mehr.";

    /** Marks the page for the browser test — and for anybody reading a support screenshot. */
    static final String KENNUNG_SPERRE = "wt-sperre";

    /** Marks the page of a dead link. */
    static final String KENNUNG_TOT = "wt-tot";

    private WatchSperrSeite() {
    }

    /**
     * A token session asked for something that is not a watch page.
     *
     * <p>The page names the two things this session can still do: back to the watch, or end the
     * session. Nothing else — it must not offer a way into the application, that is the whole
     * point of the confinement.</p>
     */
    static void nurDieUhr(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!willHtml(request)) {
            schreibe(response, "text/plain;charset=UTF-8", SATZ_NUR_DIE_UHR);
            return;
        }
        String ctx = escape(kontext(request));
        String csrf = csrfFeld(request);
        schreibe(response, "text/html;charset=UTF-8", seite(KENNUNG_SPERRE, "Nur die Uhr", """
                    <h1>Diese Sitzung ist die Uhr</h1>
                    <p>Dieses Gerät ist über den persönlichen Uhr-Link hereingekommen. Der Link
                       öffnet die Uhr — und nur die Uhr. Die angeforderte Seite bleibt deshalb
                       zu.</p>
                    <p><a class="wt-knopf" href="CTX/watch/start" id="wt-zur-uhr">Zurück zur Uhr</a></p>
                    <form method="post" action="CTX/logout" id="wt-abmelden">
                        CSRF<button type="submit" class="wt-knopf wt-leiser">Diese Sitzung beenden</button>
                    </form>
                    <p class="wt-fussnote">Abmelden beendet nur diese Sitzung auf diesem Gerät.
                       Der Uhr-Link bleibt gültig und öffnet die Uhr weiterhin — widerrufen wird
                       er in den Watch-Einstellungen am grossen Bildschirm.</p>
                """.replace("CSRF", csrf).replace("CTX", ctx)));
    }

    /**
     * The token behind this session is gone — revoked, switched off or no longer readable.
     *
     * <p>No "end this session" here: the filter has already invalidated it. What is left is the
     * ordinary sign-in, and that is what the page offers.</p>
     */
    static void linkGiltNichtMehr(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!willHtml(request)) {
            schreibe(response, "text/plain;charset=UTF-8", SATZ_LINK_TOT);
            return;
        }
        String ctx = escape(kontext(request));
        schreibe(response, "text/html;charset=UTF-8", seite(KENNUNG_TOT, SATZ_LINK_TOT, """
                    <h1>Dieser Link gilt nicht mehr.</h1>
                    <p>Der Uhr-Link dieses Geräts wurde widerrufen oder abgeschaltet. Die Sitzung
                       ist damit beendet.</p>
                    <p><a class="wt-knopf" href="CTX/login.html" id="wt-zur-anmeldung">Zur Anmeldung</a></p>
                    <p class="wt-fussnote">Ein neuer Link wird in den Watch-Einstellungen
                       ausgestellt.</p>
                """.replace("CTX", ctx)));
    }

    /**
     * Writes the answer. Always 403 — this class changes what the refusal looks like, never
     * that it is one.
     *
     * <p>{@code no-store}: the page is tied to one session and carries a CSRF token; a cached
     * copy would be shown to the next caller on a shared phone, and its token would be stale.</p>
     */
    private static void schreibe(HttpServletResponse response, String typ, String koerper) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(typ);
        response.setHeader("Cache-Control", "no-store, must-revalidate");
        byte[] bytes = koerper.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    /**
     * Whether the caller asked for a page.
     *
     * <p>A browser navigation sends {@code Accept: text/html,…}; a {@code &lt;script src&gt;},
     * a stylesheet and {@code curl} send {@code &#42;&#47;&#42;} or nothing. The test is
     * deliberately on the literal type and not on a wildcard — {@code &#42;&#47;&#42;}
     * formally accepts HTML, and answering
     * a JavaScript request with a page is how a half-loaded page looks like a broken deploy
     * instead of a locked door.</p>
     */
    private static boolean willHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    /** Context path, never {@code null}, never with a trailing slash. */
    private static String kontext(HttpServletRequest request) {
        String ctx = request.getContextPath();
        if (ctx == null || "/".equals(ctx)) {
            return "";
        }
        return ctx;
    }

    /**
     * The hidden field that makes the logout button work.
     *
     * <p>{@code /logout} is a CSRF-validated POST ({@code PlaintextSecurityConfig}: only
     * {@code /nosec/**} is exempt), so without this field the button would answer 403 and the
     * page would promise a way out that is not one. The token stands in the request because
     * {@code CsrfFilter} runs ahead of this filter; Spring Security hands it out either
     * directly or behind a {@code Supplier}, depending on the request handler — both are
     * read.</p>
     *
     * <p>If there is no token at all the field is left out rather than guessed. The button then
     * fails visibly instead of sending a value that cannot match.</p>
     */
    private static String csrfFeld(HttpServletRequest request) {
        CsrfToken token = csrfToken(request);
        if (token == null) {
            return "";
        }
        return "<input type=\"hidden\" name=\"" + escape(token.getParameterName())
                + "\" value=\"" + escape(token.getToken()) + "\"/>\n        ";
    }

    private static CsrfToken csrfToken(HttpServletRequest request) {
        Object attribut = request.getAttribute(CsrfToken.class.getName());
        if (attribut instanceof CsrfToken token) {
            return token;
        }
        if (attribut instanceof Supplier<?> lieferant && lieferant.get() instanceof CsrfToken token) {
            return token;
        }
        return null;
    }

    /**
     * The frame around both pages.
     *
     * <p>Phone measurements, because that is where this is read, and the same plain look as
     * {@code static/error.html} — this is an error page of this house, not a new design.
     * Inline style, no script: {@code style-src} carries {@code 'unsafe-inline'},
     * {@code script-src} deliberately does not ({@code PlaintextSecurityConfig.cspPolicy}), and
     * a page whose only two controls are a link and a submit button needs none.</p>
     */
    private static String seite(String kennung, String titel, String inhalt) {
        return """
                <!DOCTYPE html>
                <html lang="de">
                <head>
                    <meta charset="utf-8"/>
                    <meta name="viewport" content="width=device-width, initial-scale=1"/>
                    <meta name="robots" content="noindex,nofollow"/>
                    <meta name="referrer" content="no-referrer"/>
                    <title>TITEL</title>
                    <style>
                        :root { color-scheme: light dark; }
                        * { box-sizing: border-box; }
                        body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
                               margin: 0; min-height: 100vh; display: flex; align-items: center;
                               justify-content: center; padding: 1.25rem;
                               background: #f5f6f8; color: #2b2f36; }
                        .wt-karte { background: #fff; padding: 1.75rem 1.5rem; border-radius: 10px;
                                    max-width: 32rem; width: 100%;
                                    box-shadow: 0 1px 3px rgba(0,0,0,.12); }
                        h1 { margin: 0 0 .75rem; font-size: 1.25rem; line-height: 1.3; }
                        p  { margin: 0 0 1rem; line-height: 1.55; }
                        form { margin: 0 0 1rem; }
                        .wt-knopf { display: inline-block; padding: .7rem 1.15rem; border: 0;
                                    border-radius: 8px; background: #1a5fb4; color: #fff;
                                    font: inherit; text-decoration: none; cursor: pointer; }
                        .wt-leiser { background: #5c6370; }
                        .wt-fussnote { margin: 0; font-size: .85rem; color: #6c757d; }
                        @media (prefers-color-scheme: dark) {
                            body { background: #16181d; color: #e4e6ea; }
                            .wt-karte { background: #21242b; box-shadow: none; }
                            .wt-fussnote { color: #9aa0a8; }
                        }
                    </style>
                </head>
                <body>
                <main class="wt-karte" id="KENNUNG">
                INHALT</main>
                </body>
                </html>
                """.replace("TITEL", titel).replace("KENNUNG", kennung).replace("INHALT", inhalt);
    }

    /**
     * Escaping through Spring's own helper, the same one
     * {@code SelfServiceController.escape} uses — and for the reason noted there: CodeQL
     * recognises {@code HtmlUtils.htmlEscape} as a sanitizer, a hand-written {@code switch}
     * (as in {@code MaintenanceModeFilter}) it does not.
     */
    static String escape(String eingabe) {
        return eingabe == null ? "" : HtmlUtils.htmlEscape(eingabe);
    }
}

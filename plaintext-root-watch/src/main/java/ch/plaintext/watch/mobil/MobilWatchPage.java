/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import ch.plaintext.watch.page.WatchPage;

/**
 * A watch page that is not a Facelet but a description (Karte 1355).
 *
 * <p>It is an ordinary {@link WatchPage}: same registry, same order, same visibility rules
 * ({@code available()} for the roles, the user's own switch on top), same rotation. The only
 * difference is where it is drawn. {@link #view()} points to {@link MobilSeitenController},
 * and because every way into the watch — next, start, the phone link, the overview — only ever
 * reads {@code view()}, a page switches from JSF to this framework by implementing this
 * interface instead of {@code WatchPage}, and nothing else changes.</p>
 *
 * <h2>What a module writes</h2>
 *
 * <ul>
 *   <li>{@link #beschreibe()} — what the page shows for the signed-in user, as blocks.</li>
 *   <li>{@link #handle(String, String)} — what a button does. It runs as a {@code fetch} from
 *       the phone, without reloading the page; the framework re-renders the description
 *       afterwards and sends the new content back.</li>
 * </ul>
 *
 * <p>No markup, no bean, no Facelet, no JavaScript.</p>
 *
 * <h2>What the framework guarantees, so that a module does not have to</h2>
 *
 * <ul>
 *   <li>Neither method is called unless {@code WatchPageRegistry.sichtbar(this)} says yes —
 *       for showing <em>and</em> for acting. A page that is switched off or barred by role
 *       answers 404.</li>
 *   <li>Every action is a {@code POST} with the CSRF token of the session. Spring Security
 *       checks it before this interface is reached.</li>
 *   <li>Everything a description contains is HTML-escaped.</li>
 * </ul>
 *
 * <p>What stays with the module, as before: the user comes from {@code PlaintextSecurityHolder},
 * never from the request, and an id that arrives as {@code wert} is checked against the user
 * in the service (Karte 1195).</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface MobilWatchPage extends WatchPage {

    /** Address prefix of every page of this framework. Inside {@code /watch/}, on purpose. */
    String PFAD = "/watch/m/";

    /**
     * The framework's address for this page. Deliberately not {@code .html}: that suffix is
     * forwarded to a Facelet by {@code UrlRewriteConfig} and would never reach the controller.
     */
    @Override
    default String view() {
        return PFAD + id();
    }

    /** What the page shows, for the signed-in user. Called on every render. */
    MobilSeite beschreibe();

    /**
     * Runs one action.
     *
     * @param aktion the action name of the button, as given in {@link MobilSeite.Knopf#aktion()}
     *               or {@link MobilSeite.Eintrag#loeschAktion()}
     * @param wert   the value of the button, may be {@code null}; untrusted, it comes from the
     *               request
     * @return what to tell the user
     */
    MobilAntwort handle(String aktion, String wert);
}

/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.service;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.watch.entity.WatchUserState;
import ch.plaintext.watch.repository.WatchUserStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Remembers per user which watch page they were on, and whether the element gallery is on.
 *
 * <h2>The user always comes from the security context</h2>
 *
 * <p>Never from a request parameter. The watch views sit behind the normal sign-in, so the
 * identity is whatever Spring Security established — the same source as on every other page.
 * An earlier module in this house took the user from a request parameter and thereby let
 * anyone read anyone else's data (card 1195); this one cannot, because there is no parameter
 * to take it from.</p>
 *
 * <h2>Reading and creating are two methods, not one (card 1273)</h2>
 *
 * <p>They used to be one: {@code eigenerZustand()} created the row when it was missing, and
 * every reader went through it. That meant {@code abgeschalteteSeiten()},
 * {@code handyLinkAktiv()} and {@code handyLinkErstellt()} — queries by name — wrote to the
 * database on their first call. Sonar saw only the symptom (S2229/S6809: a {@code @Transactional}
 * method called through {@code this}, where the proxy does not apply); the defect underneath was
 * that a read wrote at all.</p>
 *
 * <p>Since then {@link #gelesenerZustand()} only reads and {@link #angelegterZustand()} only
 * creates, both are {@code private}, and the public methods call them instead of each other.
 * <b>No public method of this class calls another one</b> — that is what makes the annotations
 * effective, not a rule to satisfy. The row now comes into being where it belongs: on the first
 * write ({@code merkeSeite}, which the watch frame calls on every render).</p>
 *
 * <h2>One query per request instead of one per page (card 1273)</h2>
 *
 * <p>{@code WatchPageRegistry.sichtbar} asks {@link #seiteAktiv(String)} for every page and is
 * evaluated on every render — deliberately uncached there, so a page switched off mid-session
 * disappears at once. With eight watch pages that used to be eight round trips to the database
 * per render, plus the loops in {@code WatchUebersichtBean} and {@code WatchSettingsBean}.</p>
 *
 * <p>The state is therefore held for the duration of <b>one request</b>, which is exactly as
 * long as the answer cannot change: within one request nobody else switches this user's pages.
 * Measured in {@code WatchAbfragezahlTest}: eight lookups before, one after.</p>
 *
 * <p><b>Without a request context nothing is cached</b> — a background job or a unit test asks
 * every time, which is the safe direction: a stale answer needs a request to live in, and there
 * is none. Every write clears the entry, so a read after a write in the same request sees the
 * new value.</p>
 *
 * <p>That the context exists on a watch request is not an assumption but follows from the module
 * itself: {@code WatchSettingsBean} and {@code WatchFrameBean} are {@code @Scope("session")}, and
 * Spring resolves a session scope through {@code RequestContextHolder}. If it were empty here,
 * those beans could not be built at all. The JSF path is covered too, because Boot's
 * {@code RequestContextFilter} sits in front of every servlet, not only the dispatcher.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchStateService {

    /** Key of the per-request entry. Class-qualified so no other module can collide with it. */
    private static final String ZWISCHENSPEICHER = WatchStateService.class.getName() + ".zustand";

    private final WatchUserStateRepository repository;

    /**
     * State of the signed-in user, <b>read only</b>. Empty if nobody is signed in or the user has
     * no row yet — this method does not create one; see the class comment.
     */
    @Transactional(readOnly = true)
    public Optional<WatchUserState> eigenerZustand() {
        return gelesenerZustand();
    }

    /**
     * Id of the page last shown, if there is one.
     *
     * <p>Deliberately returns the id and not the page. This service must not know the page
     * registry: the registry collects every {@code WatchPage}, one of them ({@code WatchTestPage})
     * asks this service whether it is switched on, and the circle would close — Spring refused to
     * start the application with
     * {@code BeanCurrentlyInCreationException: watchPageRegistry}. Resolving an id to a page is
     * the job of whoever holds both, and that is the frame bean.</p>
     */
    @Transactional(readOnly = true)
    public Optional<String> gemerkteSeitenId() {
        return gelesenerZustand().map(WatchUserState::getAktuelleSeite).filter(id -> !id.isBlank());
    }

    /**
     * Remember the page.
     *
     * <p>Writes only when the value actually changes — the watch view calls this on every
     * render, and an unconditional save would put a row update behind every screen refresh.</p>
     *
     * <p>The comparison runs against the already loaded state first (card 1273). In the ordinary
     * render the page has not changed, and then this method costs nothing on top of the one
     * lookup the render needs anyway. Only a real change loads the row for writing — and creates
     * it if the user has none yet, which is where the row comes into being at all.</p>
     */
    @Transactional
    public void merkeSeite(String seitenId) {
        if (seitenId == null || seitenId.isBlank()) {
            return;
        }
        Optional<WatchUserState> bekannt = gelesenerZustand();
        if (bekannt.isPresent() && seitenId.equals(bekannt.get().getAktuelleSeite())) {
            return;
        }
        angelegterZustand().ifPresent(z -> {
            if (!seitenId.equals(z.getAktuelleSeite())) {
                z.setAktuelleSeite(seitenId);
                repository.save(z);
                vergessen();
                log.debug("Watch: Position von {} auf {} gemerkt", z.getBenutzer(), seitenId);
            }
        });
    }

    /**
     * Whether the page with this id is switched on for the signed-in user (cards 1257/1260).
     *
     * <p><b>This is not a permission.</b> It says only what the user wants to see. Whether they
     * may see it is decided by {@code WatchPage.available()}, which asks the page guard; the two
     * are evaluated together in {@code WatchPageRegistry.sichtbar} and both have to say yes. A
     * page locked by role stays locked no matter what stands here.</p>
     *
     * <p>Unknown to the user (no row yet, nothing switched off) means <b>on</b> — see the
     * migration: the off-list, not the on-list, is stored.</p>
     */
    public boolean seiteAktiv(String seitenId) {
        if (seitenId == null || seitenId.isBlank()) {
            return false;
        }
        return !geleseneAbschaltungen().contains(seitenId);
    }

    /** The ids the signed-in user has switched off; empty when nobody is signed in. */
    public Set<String> abgeschalteteSeiten() {
        return geleseneAbschaltungen();
    }

    /**
     * Switches one page on or off for the signed-in user.
     *
     * <p>Writes only on a real change, for the same reason as {@link #merkeSeite(String)}.</p>
     */
    @Transactional
    public void setzeSeite(String seitenId, boolean aktiv) {
        if (seitenId == null || seitenId.isBlank()) {
            return;
        }
        angelegterZustand().ifPresent(z -> {
            Set<String> aus = new LinkedHashSet<>(zerlege(z.getAbgeschalteteSeiten()));
            boolean geaendert = aktiv ? aus.remove(seitenId) : aus.add(seitenId);
            if (!geaendert) {
                return;
            }
            z.setAbgeschalteteSeiten(aus.isEmpty() ? null : String.join(",", aus));
            repository.save(z);
            vergessen();
            log.debug("Watch: Seite {} fuer {} {}", seitenId, z.getBenutzer(), aktiv ? "an" : "aus");
        });
    }

    /**
     * Splits the stored list. Blank entries are dropped, so a stray comma cannot switch off a
     * page called "".
     */
    private static Set<String> zerlege(String gespeichert) {
        if (gespeichert == null || gespeichert.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(gespeichert.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---- Handy-Link (Karte 1257) --------------------------------------------------------------

    /** Whether the personal phone link of the signed-in user is switched on. */
    public boolean handyLinkAktiv() {
        return gelesenerZustand().map(WatchUserState::isHandyLinkAktiv).orElse(false);
    }

    /** When the currently valid phone link was issued, if there is one. */
    public Optional<LocalDateTime> handyLinkErstellt() {
        return gelesenerZustand().map(WatchUserState::getHandyLinkErstellt);
    }

    /**
     * Records a freshly issued phone link. Deliberately takes only the {@code jti}, never the
     * token — see {@link WatchUserState#getHandyLinkJti()}.
     */
    @Transactional
    public void merkeHandyLink(String jti) {
        angelegterZustand().ifPresent(z -> {
            z.setHandyLinkAktiv(true);
            z.setHandyLinkJti(jti);
            z.setHandyLinkErstellt(LocalDateTime.now());
            repository.save(z);
            vergessen();
        });
    }

    /** Switches the phone link off. The revocation of the token itself is the caller's job. */
    @Transactional
    public void schalteHandyLinkAb() {
        angelegterZustand().ifPresent(z -> {
            z.setHandyLinkAktiv(false);
            z.setHandyLinkJti(null);
            z.setHandyLinkErstellt(null);
            repository.save(z);
            vergessen();
        });
    }

    /**
     * Whether the phone link of <b>this</b> user is switched on — for the token path, where
     * nobody is signed in and the user therefore cannot come from the security context.
     *
     * <p>Creates nothing: a user without a row has never generated a link, and a request that
     * carries a token for them must not bring a row into being.</p>
     */
    public boolean handyLinkAktivFuer(String benutzer) {
        if (benutzer == null || benutzer.isBlank()) {
            return false;
        }
        return repository.findByBenutzerAndDeletedFalse(benutzer)
                .map(WatchUserState::isHandyLinkAktiv).orElse(false);
    }

    // ---- intern: lesen, anlegen, zwischenspeichern ---------------------------------------------

    /**
     * The switched-off ids, with the guard that belongs to the hot path.
     *
     * <p>Private and unannotated on purpose: {@link #seiteAktiv(String)} and
     * {@link #abgeschalteteSeiten()} both need it, and a call between two public methods would
     * bypass the Spring proxy — the very thing this class was rebuilt to stop doing.</p>
     */
    private Set<String> geleseneAbschaltungen() {
        try {
            return gelesenerZustand().map(z -> zerlege(z.getAbgeschalteteSeiten())).orElseGet(Set::of);
        } catch (RuntimeException e) {
            // Never lets an exception out: this runs for every page on every render. If the
            // selection cannot be read, the user sees what the access rules allow — the state
            // before this card — instead of an empty watch.
            log.warn("Watch: Seitenauswahl nicht lesbar, zeige alles Erlaubte: {}", e.toString());
            return Set.of();
        }
    }

    /**
     * Loads the state of the signed-in user and holds it for the rest of the request. Creates
     * nothing.
     *
     * <p>The entry carries the user name and is only used when it matches: within one request
     * the identity normally does not change, but the token path of card 1257 establishes it
     * mid-request, and an entry from before that would answer for the wrong person.</p>
     */
    private Optional<WatchUserState> gelesenerZustand() {
        String benutzer = PlaintextSecurityHolder.getUser();
        if (benutzer == null || benutzer.isBlank()) {
            return Optional.empty();
        }
        RequestAttributes anfrage = RequestContextHolder.getRequestAttributes();
        if (anfrage == null) {
            return repository.findByBenutzerAndDeletedFalse(benutzer);
        }
        if (anfrage.getAttribute(ZWISCHENSPEICHER, RequestAttributes.SCOPE_REQUEST)
                instanceof Eintrag e && benutzer.equals(e.benutzer())) {
            return Optional.ofNullable(e.zustand());
        }
        Optional<WatchUserState> frisch = repository.findByBenutzerAndDeletedFalse(benutzer);
        anfrage.setAttribute(ZWISCHENSPEICHER, new Eintrag(benutzer, frisch.orElse(null)),
                RequestAttributes.SCOPE_REQUEST);
        return frisch;
    }

    /**
     * The state of the signed-in user, created if it is not there yet. Empty only when nobody is
     * signed in.
     *
     * <p>Deliberately bypasses the per-request entry and loads afresh: this runs inside the
     * caller's write transaction, and what is written has to be the row of this transaction, not
     * an instance detached from an earlier read.</p>
     */
    private Optional<WatchUserState> angelegterZustand() {
        String benutzer = PlaintextSecurityHolder.getUser();
        if (benutzer == null || benutzer.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(repository.findByBenutzerAndDeletedFalse(benutzer)
                .orElseGet(() -> {
                    WatchUserState neu = new WatchUserState();
                    neu.setBenutzer(benutzer);
                    neu.setMandat(PlaintextSecurityHolder.getMandat());
                    return repository.save(neu);
                }));
    }

    /** Drops the per-request entry after a write, so a following read sees the new value. */
    private static void vergessen() {
        RequestAttributes anfrage = RequestContextHolder.getRequestAttributes();
        if (anfrage != null) {
            anfrage.removeAttribute(ZWISCHENSPEICHER, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /**
     * What is held for one request: whose state it is, and the state itself.
     *
     * <p>{@code zustand == null} means "looked it up, there is no row" — that is a cache hit too,
     * and the distinction matters: without it every read of a user who has no row yet would go to
     * the database again. Deliberately not an {@code Optional} component: an {@code Optional} in a
     * field is its own Sonar finding, and it would buy nothing here.</p>
     */
    private record Eintrag(String benutzer, WatchUserState zustand) {
    }
}

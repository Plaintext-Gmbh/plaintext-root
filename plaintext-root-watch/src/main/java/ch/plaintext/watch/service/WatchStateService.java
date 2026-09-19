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
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchStateService {

    private final WatchUserStateRepository repository;

    /** State of the signed-in user, created on first use. Empty if nobody is signed in. */
    @Transactional
    public Optional<WatchUserState> eigenerZustand() {
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
    @Transactional
    public Optional<String> gemerkteSeitenId() {
        return eigenerZustand().map(WatchUserState::getAktuelleSeite).filter(id -> !id.isBlank());
    }

    /**
     * Remember the page.
     *
     * <p>Writes only when the value actually changes — the watch view calls this on every
     * render, and an unconditional save would put a row update behind every screen refresh.</p>
     */
    @Transactional
    public void merkeSeite(String seitenId) {
        if (seitenId == null || seitenId.isBlank()) {
            return;
        }
        eigenerZustand().ifPresent(z -> {
            if (!seitenId.equals(z.getAktuelleSeite())) {
                z.setAktuelleSeite(seitenId);
                repository.save(z);
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
        return !abgeschalteteSeiten().contains(seitenId);
    }

    /** The ids the signed-in user has switched off; empty when nobody is signed in. */
    public Set<String> abgeschalteteSeiten() {
        try {
            return eigenerZustand().map(z -> zerlege(z.getAbgeschalteteSeiten())).orElseGet(Set::of);
        } catch (RuntimeException e) {
            // Never lets an exception out: this runs for every page on every render. If the
            // selection cannot be read, the user sees what the access rules allow — the state
            // before this card — instead of an empty watch.
            log.warn("Watch: Seitenauswahl nicht lesbar, zeige alles Erlaubte: {}", e.toString());
            return Set.of();
        }
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
        eigenerZustand().ifPresent(z -> {
            Set<String> aus = new LinkedHashSet<>(zerlege(z.getAbgeschalteteSeiten()));
            boolean geaendert = aktiv ? aus.remove(seitenId) : aus.add(seitenId);
            if (!geaendert) {
                return;
            }
            z.setAbgeschalteteSeiten(aus.isEmpty() ? null : String.join(",", aus));
            repository.save(z);
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
        return eigenerZustand().map(WatchUserState::isHandyLinkAktiv).orElse(false);
    }

    /** When the currently valid phone link was issued, if there is one. */
    public Optional<LocalDateTime> handyLinkErstellt() {
        return eigenerZustand().map(WatchUserState::getHandyLinkErstellt);
    }

    /**
     * Records a freshly issued phone link. Deliberately takes only the {@code jti}, never the
     * token — see {@link WatchUserState#getHandyLinkJti()}.
     */
    @Transactional
    public void merkeHandyLink(String jti) {
        eigenerZustand().ifPresent(z -> {
            z.setHandyLinkAktiv(true);
            z.setHandyLinkJti(jti);
            z.setHandyLinkErstellt(LocalDateTime.now());
            repository.save(z);
        });
    }

    /** Switches the phone link off. The revocation of the token itself is the caller's job. */
    @Transactional
    public void schalteHandyLinkAb() {
        eigenerZustand().ifPresent(z -> {
            z.setHandyLinkAktiv(false);
            z.setHandyLinkJti(null);
            z.setHandyLinkErstellt(null);
            repository.save(z);
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
}

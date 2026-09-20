/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import ch.plaintext.watch.service.WatchStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Collects every {@link WatchPage} in the application and moves between them.
 *
 * <h2>Why the wrap-around is deliberate</h2>
 *
 * <p>On a screen this small there is no room for a page list, so the only navigation is
 * next/previous. If the last page did not wrap to the first, the user would reach a dead end
 * and have to tap their way back. Wrapping keeps every page reachable with at most
 * <em>n/2</em> taps.</p>
 *
 * <h2>Unavailable pages</h2>
 *
 * <p>Visibility is evaluated on every move, not cached. A page that is switched off mid-session
 * disappears immediately instead of showing an empty screen on the next tap.</p>
 *
 * <p>Two independent questions are asked, and both have to say yes ({@link #sichtbar}):</p>
 * <ol>
 *   <li>{@link WatchPage#available()} — may the user see the module at all? The module answers
 *       it against its own menu roles.</li>
 *   <li>{@code WatchStateService.seiteAktiv(id)} — does the user <em>want</em> to see it? That
 *       is the per-user selection from card 1257.</li>
 * </ol>
 *
 * <h2>Why the selection is evaluated here and not in every page (card 1257)</h2>
 *
 * <p>There are seven pages in three repositories, and each would have had to remember to ask
 * the same second question — a module that forgets it silently ignores the switch. Here it is
 * one place, and a new module page is covered the day it is written.</p>
 *
 * <p><b>The direction of the dependency is not negotiable.</b> The registry may know the state
 * service; the state service must <b>not</b> know the registry. {@code WatchTestPage} asks the
 * service about itself, the registry collects the pages — the other direction closes the circle
 * and Spring refuses to build the context at all
 * ({@code BeanCurrentlyInCreationException: watchPageRegistry}, card 1245). A test in
 * {@code WatchStateServiceTest} pins it.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
@Slf4j
public class WatchPageRegistry {

    private final List<WatchPage> alle;

    /**
     * The per-user selection. {@code null} is allowed and means "no selection is evaluated" —
     * that is the case in unit tests that build the registry with pages only.
     */
    private final WatchStateService zustand;

    public WatchPageRegistry(List<WatchPage> seiten, WatchStateService zustand) {
        this.alle = seiten.stream().sorted(Comparator.comparingInt(WatchPage::order)
                .thenComparing(WatchPage::id)).toList();
        this.zustand = zustand;
        log.info("Watch-Seiten erkannt: {}", this.alle.stream().map(WatchPage::id).toList());
    }

    /** Every page, ordered — including those currently unavailable. */
    public List<WatchPage> alle() {
        return alle;
    }

    /**
     * Whether the current user may see this page <b>and</b> has it switched on.
     *
     * <p>The single place where the two questions are put together. Whoever asks
     * {@code available()} on its own gets only half the answer and lets a switched-off page
     * through — which is exactly what a direct call of the view id used to do.</p>
     *
     * <p>Never lets an exception out: this decides whether the watch shows anything at all.</p>
     */
    public boolean sichtbar(WatchPage seite) {
        if (seite == null) {
            return false;
        }
        try {
            if (!seite.available()) {
                return false;
            }
        } catch (Exception e) {
            log.warn("Watch: Zugriffsregel von {} nicht auswertbar: {}", seite.id(), e.toString());
            return false;
        }
        // Fail-OPEN on purpose, and only on this half: without a state service (unit test) or
        // with an unreadable selection the user sees what the access rules allow — the state
        // before card 1257. Fail-closed here would turn a database hiccup into an empty watch,
        // while the half above, the one that actually protects something, stays fail-closed.
        return zustand == null || zustand.seiteAktiv(seite.id());
    }

    /**
     * The pages of the rotation: visible and taking part in next/previous. This is what the
     * "x/n" in the header counts.
     */
    public List<WatchPage> verfuegbare() {
        return alle.stream().filter(this::sichtbar).filter(WatchPage::imUmlauf).toList();
    }

    public Optional<WatchPage> byId(String id) {
        return alle.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** The page to show when nothing is remembered yet: the first available one. */
    public Optional<WatchPage> erste() {
        return verfuegbare().stream().findFirst();
    }

    /**
     * Where the watch starts: the remembered page if it is still visible, otherwise the first
     * one (card 1289).
     *
     * <h2>Why this is a method and not four lines twice</h2>
     *
     * <p>The rule existed, but only inside {@code WatchFrameBean.seitenaufruf()}, and only on
     * the branch that runs when a watch page is called <b>without naming itself</b> — a way
     * nobody actually goes. The phone link and the address for the home screen both named
     * {@code /watch/home.html} outright, so the remembered page never decided anything at
     * start. {@code WatchStartController} is the second caller of the same rule, and two copies
     * of it would drift: a page that is switched off has to fall back here exactly as it does
     * in the frame, or the start would send the user to a page the frame then redirects away
     * from again.</p>
     *
     * <p>Deliberately takes the id rather than asking the state service itself, although the
     * registry holds one: the frame bean has already read the state for this request, and a
     * second lookup would be a second query per render — the one thing card 1273 took out of
     * this path.</p>
     *
     * @param gemerkteId the id stored for this user, {@code null} or blank when there is none
     */
    public Optional<WatchPage> einstieg(String gemerkteId) {
        if (gemerkteId == null || gemerkteId.isBlank()) {
            return erste();
        }
        return byId(gemerkteId).filter(this::sichtbar).or(this::erste);
    }

    /**
     * The page after {@code aktuelleId}, wrapping around at the end.
     *
     * <p>An unknown or no longer available id falls back to the first page rather than
     * throwing — the id comes from storage and may name a page whose module was switched off
     * since it was written.</p>
     */
    public Optional<WatchPage> naechste(String aktuelleId) {
        return nachbar(aktuelleId, +1);
    }

    /** The page before {@code aktuelleId}, wrapping around at the start. */
    public Optional<WatchPage> vorherige(String aktuelleId) {
        return nachbar(aktuelleId, -1);
    }

    private Optional<WatchPage> nachbar(String aktuelleId, int richtung) {
        List<WatchPage> sichtbar = verfuegbare();
        if (sichtbar.isEmpty()) {
            return Optional.empty();
        }
        int i = -1;
        for (int k = 0; k < sichtbar.size(); k++) {
            if (sichtbar.get(k).id().equals(aktuelleId)) {
                i = k;
                break;
            }
        }
        if (i < 0) {
            return Optional.of(sichtbar.get(0));
        }
        int ziel = Math.floorMod(i + richtung, sichtbar.size());
        return Optional.of(sichtbar.get(ziel));
    }
}

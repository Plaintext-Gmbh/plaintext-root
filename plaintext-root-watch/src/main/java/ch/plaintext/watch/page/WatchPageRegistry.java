/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

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
 * <p>{@link WatchPage#available()} is evaluated on every move, not cached. A page that is
 * switched off mid-session (the element gallery, for instance) disappears immediately instead
 * of showing an empty screen on the next tap.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
@Slf4j
public class WatchPageRegistry {

    private final List<WatchPage> alle;

    public WatchPageRegistry(List<WatchPage> seiten) {
        this.alle = seiten.stream().sorted(Comparator.comparingInt(WatchPage::order)
                .thenComparing(WatchPage::id)).toList();
        log.info("Watch-Seiten erkannt: {}", this.alle.stream().map(WatchPage::id).toList());
    }

    /** Every page, ordered — including those currently unavailable. */
    public List<WatchPage> alle() {
        return alle;
    }

    /** Only the pages the current user may see, ordered. */
    public List<WatchPage> verfuegbare() {
        return alle.stream().filter(WatchPage::available).toList();
    }

    public Optional<WatchPage> byId(String id) {
        return alle.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** The page to show when nothing is remembered yet: the first available one. */
    public Optional<WatchPage> erste() {
        return verfuegbare().stream().findFirst();
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

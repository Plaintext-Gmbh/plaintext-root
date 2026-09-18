/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import ch.plaintext.watch.service.WatchStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The element gallery: every control that works on a small display, with a label saying what it
 * is and what it costs. Meant as a reference while building a new watch page.
 *
 * <p>It is in the way during everyday use, so it is off unless the user switches it on in the
 * watch settings. {@link #available()} asks the service on <em>every</em> call rather than
 * caching — switching it off in one tab must take effect in the other.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WatchTestPage implements WatchPage {

    private final WatchStateService zustand;

    @Override
    public String id() {
        return "elemente";
    }

    @Override
    public String title() {
        return "Elemente";
    }

    @Override
    public String view() {
        return "/watch/elemente.xhtml";
    }

    @Override
    public int order() {
        return 900;
    }

    /**
     * Never lets an exception out. This page sits in the page stack of every user; if the state
     * service stumbles, the gallery must disappear — not take the whole stack with it.
     */
    @Override
    public boolean available() {
        try {
            return zustand.testseiteAktiv();
        } catch (Exception e) {
            log.warn("Watch: Verfuegbarkeit der Elementseite nicht feststellbar: {}", e.toString());
            return false;
        }
    }
}

/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The overview: which pages this user sees, with a switch each — and below it the element
 * gallery, every control that works on a small display with a label saying what it is and what
 * it costs (card 1247).
 *
 * <h2>Why it is out of the rotation (card 1260)</h2>
 *
 * <p>"die demoseite in uebersicht als normale seite listen die man ein und ausschalten kann"
 * — Daniel, 19.09.2026. Until then the gallery ran along as a seventh equal page and counted
 * in "x/7", so everybody swiped past a reference sheet every day. {@link #imUmlauf()} takes it
 * out of the rotation and out of the count; it is reached by the link in the header of every
 * page.</p>
 *
 * <h2>Why it can no longer be switched off</h2>
 *
 * <p>It is the only place with the switches. A page that can lock away the way back to itself
 * is a trap, so {@link #available()} is unconditionally {@code true} and the overview is
 * absent from its own switch list. Its old single switch
 * ({@code WatchUserState.testseiteAktiv}) is gone, replaced by the general per-user selection
 * this page now operates.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
@Slf4j
public class WatchTestPage implements WatchPage {

    /** Id stays "elemente": it is persisted per user as the last page shown (card 1245). */
    @Override
    public String id() {
        return "elemente";
    }

    @Override
    public String title() {
        return "Seiten";
    }

    @Override
    public String view() {
        return "/watch/elemente.xhtml";
    }

    @Override
    public int order() {
        return 900;
    }

    /** Outside the rotation: reachable by its address and by the header link, never by swiping. */
    @Override
    public boolean imUmlauf() {
        return false;
    }
}

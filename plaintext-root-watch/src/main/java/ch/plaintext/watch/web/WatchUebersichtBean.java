/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.service.WatchStateService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The page switches on the watch itself (card 1260).
 *
 * <p>Daniel, 19.09.2026: „die demoseite in uebersicht als normale seite listen die man ein und
 * ausschalten kann."</p>
 *
 * <h2>The same selection, not a second one</h2>
 *
 * <p>Everything here goes through {@code WatchStateService.setzeSeite} — the same call the
 * settings page in the browser makes. There is no second store and no second rule: what is
 * switched off here is switched off there, in the rotation, and on a direct call of the view
 * id.</p>
 *
 * <p>One tap per page instead of a list with a save button: on a 184 px display a form with
 * seven checkboxes and a button is a form nobody fills in. Each tap writes at once, which is
 * also why the page can afford to re-read its list on every render.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component("watchUebersichtBean")
@Scope("view")
@Slf4j
public class WatchUebersichtBean implements Serializable {

    private static final long serialVersionUID = 1L;

    // Feldinjektion statt Konstruktor: die Bean liegt im View-State und muss serialisierbar
    // sein; `final transient` bliebe nach einer Deserialisierung dauerhaft null.
    @Autowired
    private transient WatchStateService zustand;

    @Autowired
    private transient WatchPageRegistry registry;

    /**
     * Every page that may be switched.
     *
     * <p>Left out: pages the access rules forbid anyway — a switch that changes nothing is worse
     * than none — and the overview itself, which is the only way back to these switches.</p>
     */
    public List<Eintrag> getSeiten() {
        List<Eintrag> alle = new ArrayList<>();
        for (WatchPage s : registry.alle()) {
            if (!s.imUmlauf() || !erlaubt(s)) {
                continue;
            }
            alle.add(new Eintrag(s.id(), s.title(), zustand.seiteAktiv(s.id())));
        }
        return alle;
    }

    private boolean erlaubt(WatchPage s) {
        try {
            return s.available();
        } catch (Exception e) {
            log.warn("Watch: Zugriffsregel von {} nicht auswertbar: {}", s.id(), e.toString());
            return false;
        }
    }

    /** Flips one page and writes it immediately. */
    public void schalte(String seitenId) {
        boolean neu = !zustand.seiteAktiv(seitenId);
        zustand.setzeSeite(seitenId, neu);
        FacesMessages.info(neu ? "an" : "aus");
    }

    /**
     * One row. Only what the small display shows.
     *
     * <h2>Why there is no helper method on this record</h2>
     *
     * <p>EL resolves a record through {@code RecordELResolver}, and that resolver knows
     * <b>only the record components</b> — an added {@code getSchalterText()} is simply not a
     * readable property, and the page dies with
     * {@code PropertyNotFoundException} → HTTP 500 at render time. Measured on 19.09.2026: the
     * page-walkthrough (AllPagesSmokePlaywrightIT) caught exactly that on
     * {@code /watch/elemente.xhtml}; nothing in the unit tests did, because in Java the method
     * is perfectly callable.</p>
     *
     * <p>Whatever the view needs is therefore a component here, or it is computed in the view.
     * {@code WatchUebersichtEintragVertragTest} holds the expressions in the page against the
     * components of this record.</p>
     */
    public record Eintrag(String id, String titel, boolean aktiv) implements Serializable {
    }
}

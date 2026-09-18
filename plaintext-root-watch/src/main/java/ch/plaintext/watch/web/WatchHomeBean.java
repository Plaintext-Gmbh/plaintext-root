/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.watch.page.WatchWidget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Collects the widgets for the watch home screen.
 *
 * <p>A widget that throws is dropped rather than taking the whole screen down with it: on a
 * watch, a blank page gives the user nothing to act on, while three of four tiles still do.</p>
 */
@Component("watchHomeBean")
@Scope("view")
@Slf4j
public class WatchHomeBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * {@code required = false} ist hier keine Bequemlichkeit, sondern Bedingung: solange kein
     * Modul ein {@link WatchWidget} beisteuert, findet Spring fuer diese Liste keinen Kandidaten
     * und der Anwendungsstart bricht ab. Das Watch-Modul soll aber auch allein lauffaehig sein —
     * dann zeigt die Startseite eben keine Kacheln. Feldinjektion statt Konstruktor, weil die
     * Bean im View-State liegt und {@code final transient} nach einer Deserialisierung dauerhaft
     * null bliebe.
     */
    @Autowired(required = false)
    private transient List<WatchWidget> alle;

    public List<Kachel> getKacheln() {
        if (alle == null) {
            return List.of();
        }
        return alle.stream()
                .filter(this::istVerfuegbar)
                .sorted(Comparator.comparingInt(WatchWidget::order).thenComparing(WatchWidget::id))
                .map(this::lies)
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean istVerfuegbar(WatchWidget w) {
        try {
            return w.available();
        } catch (Exception e) {
            log.warn("Watch-Widget {} meldet Verfuegbarkeit nicht: {}", w.id(), e.toString());
            return false;
        }
    }

    private Kachel lies(WatchWidget w) {
        try {
            return new Kachel(w.id(), w.label(), w.value());
        } catch (Exception e) {
            log.warn("Watch-Widget {} liefert keinen Wert: {}", w.id(), e.toString());
            return null;
        }
    }

    /** Wie viele Spalten die Kachelreihe bekommt — bei drei und mehr je drei nebeneinander. */
    public String getSpaltenKlasse() {
        return getKacheln().size() >= 3 ? "w-widgets w-3" : "w-widgets";
    }

    public record Kachel(String id, String label, String value) {
    }
}

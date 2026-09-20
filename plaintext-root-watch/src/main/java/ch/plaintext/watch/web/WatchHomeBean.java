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
 *
 * <h2>Why field injection stays here (java:S6813, card 1273)</h2>
 *
 * <p>This bean is <b>view-scoped and serializable</b>, and with server-side state saving the JSF
 * view state is held in the session and written out with it. On a deserialization <b>no
 * constructor runs</b>: a service field set only through the
 * constructor stays {@code null} forever, and being {@code final} nothing can set it afterwards,
 * not even the context. A {@code NotSerializableException} would thereby turn into a permanent
 * {@code NullPointerException} (cards 915/1246). Field injection ({@code @Autowired}, not
 * {@code final}) lets the context refill the field after a deserialization — that is the house
 * rule, and {@code PlaintextSessionBeanSerialisierbarTest} enforces it as a build-breaking
 * guard.</p>
 *
 * <p>{@code java:S6813} demands the exact opposite at these fields, so both cannot hold at once.
 * The guard wins: it protects against a defect that is silent and permanent, the rule protects a
 * style. The suppression sits on the class because every injected service of such a bean falls
 * under the house rule — card 1273 carries the measurement and the decision.</p>
 */
@Component("watchHomeBean")
@Scope("view")
@Slf4j
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
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

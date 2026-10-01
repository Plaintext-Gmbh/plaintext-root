/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import ch.plaintext.boot.security.PageAccessGuardService;
import ch.plaintext.watch.mobil.MobilAntwort;
import ch.plaintext.watch.mobil.MobilSeite;
import ch.plaintext.watch.mobil.MobilWatchPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The landing page of the watch view: a few widgets and the way into the other pages.
 *
 * <p>Always first ({@code order 0}) — it is the fallback whenever a remembered page has
 * disappeared — and available to whoever may use the watch at all ({@link WatchUhrZugriff}).</p>
 *
 * <h2>Since card 1387 a page of the mobile framework</h2>
 *
 * <p>Daniel, 01.10.2026: „Das neue design für die watch passt gut bitte alles umstellen". What
 * {@code home.xhtml} and {@code WatchHomeBean} did is here now, unchanged in substance: the tiles
 * every module contributes as a {@link WatchWidget}, ordered, a widget that throws dropped rather
 * than taking the whole screen down with it — on a watch a blank page leaves nothing to act on,
 * while three of four tiles still do. The old address {@code /watch/home.html} keeps working
 * through {@code MobilAltadressenFilter}.</p>
 */
@Component
@Slf4j
public class WatchHomePage implements MobilWatchPage {

    static final String LEER = "Noch keine Kacheln. Module liefern sie über WatchWidget.";

    /**
     * The widgets. A provider and not a list: as long as no module contributes a widget there is
     * no candidate, and a plain {@code List} injection would refuse to start — the watch module
     * has to run on its own, with an empty home screen.
     */
    private final Supplier<List<WatchWidget>> widgets;

    private final ObjectProvider<PageAccessGuardService> guard;

    @Autowired
    public WatchHomePage(ObjectProvider<WatchWidget> widgets, ObjectProvider<PageAccessGuardService> guard) {
        this(() -> widgets.stream().toList(), guard);
    }

    WatchHomePage(Supplier<List<WatchWidget>> widgets, ObjectProvider<PageAccessGuardService> guard) {
        this.widgets = widgets;
        this.guard = guard;
    }

    @Override
    public String id() {
        return "home";
    }

    @Override
    public String title() {
        return "Übersicht";
    }

    @Override
    public int order() {
        return 0;
    }

    /** The roles of the watch settings, as the page guard held them for home.xhtml (card 1245). */
    @Override
    public boolean available() {
        return WatchUhrZugriff.erlaubt(guard);
    }

    @Override
    public MobilSeite beschreibe() {
        return MobilSeite.neu().kacheln(kacheln(), LEER).bauen();
    }

    /** The home screen has no buttons of its own; "weiter" belongs to the frame. */
    @Override
    public MobilAntwort handle(String aktion, String wert) {
        return MobilAntwort.fehler("Unbekannte Aktion.");
    }

    List<MobilSeite.Kachel> kacheln() {
        List<WatchWidget> alle = widgets.get();
        if (alle == null) {
            return List.of();
        }
        return alle.stream()
                .filter(Objects::nonNull)
                .filter(WatchHomePage::istVerfuegbar)
                .sorted(Comparator.comparingInt(WatchWidget::order).thenComparing(WatchWidget::id))
                .map(WatchHomePage::lies)
                .filter(Objects::nonNull)
                .toList();
    }

    private static boolean istVerfuegbar(WatchWidget w) {
        try {
            return w.available();
        } catch (Exception e) {
            log.warn("Watch-Widget {} meldet Verfuegbarkeit nicht: {}", w.id(), e.toString());
            return false;
        }
    }

    private static MobilSeite.Kachel lies(WatchWidget w) {
        try {
            return new MobilSeite.Kachel(w.label(), w.value());
        } catch (Exception e) {
            log.warn("Watch-Widget {} liefert keinen Wert: {}", w.id(), e.toString());
            return null;
        }
    }
}

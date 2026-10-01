/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import ch.plaintext.boot.security.PageAccessGuardService;
import ch.plaintext.watch.mobil.MobilAntwort;
import ch.plaintext.watch.mobil.MobilSeite;
import ch.plaintext.watch.mobil.MobilWatchPage;
import ch.plaintext.watch.service.WatchStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The overview: which pages this user sees, with a switch each — and below it the gallery of
 * the building blocks, every one of them live on a small display (card 1247, since card 1387 the
 * blocks of the mobile framework).
 *
 * <h2>Why it is out of the rotation (card 1260)</h2>
 *
 * <p>"die demoseite in uebersicht als normale seite listen die man ein und ausschalten kann"
 * — Daniel, 19.09.2026. Until then the gallery ran along as a seventh equal page and counted
 * in "x/7", so everybody swiped past a reference sheet every day. {@link #imUmlauf()} takes it
 * out of the rotation and out of the count; it is reached by the long press on the forward
 * button of every page.</p>
 *
 * <h2>Why it can no longer be switched off</h2>
 *
 * <p>It is the only place with the switches. A page that can lock away the way back to itself
 * is a trap, so the overview is absent from its own switch list. {@link #available()} asks only
 * the roles of the watch settings ({@link WatchUhrZugriff}) — the rule the page guard applied to
 * {@code elemente.xhtml} through its alias until card 1387.</p>
 *
 * <h2>Since card 1387 a page of the mobile framework</h2>
 *
 * <p>What {@code elemente.xhtml}, {@code WatchUebersichtBean} and {@code WatchElementeBean} did is
 * here now. The switches are unchanged in substance: one tap per page, written at once through
 * {@link WatchStateService#setzeSeite} — the same call the settings page in the browser makes, no
 * second store. The gallery used to show Facelet controls next to their PrimeFaces equivalent
 * (card 1247); that comparison is settled, and what somebody building a page needs to see now is
 * the vocabulary of {@link MobilSeite}. Its buttons do nothing but say what was tapped.</p>
 *
 * <h2>Why the registry comes through a provider</h2>
 *
 * <p>The registry collects every {@link WatchPage}, this one included. Asking for the registry
 * in the constructor would close the circle ({@code BeanCurrentlyInCreationException}, card
 * 1245); the provider resolves it only when the page is drawn.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
@Slf4j
public class WatchTestPage implements MobilWatchPage {

    static final String SCHALTE = "schalte";
    static final String TIPPE = "tippe";
    static final String AENDERE = "aendere";
    static final String LOESCHE = "loesche";

    static final String HINWEIS = "Diese Übersicht selbst läuft nicht im Umlauf mit und lässt sich nicht "
            + "abschalten — sonst gäbe es keinen Weg zurück zu den Schaltern.";

    private final ObjectProvider<WatchPageRegistry> registry;
    private final WatchStateService zustand;
    private final ObjectProvider<PageAccessGuardService> guard;

    public WatchTestPage(ObjectProvider<WatchPageRegistry> registry, WatchStateService zustand,
                         ObjectProvider<PageAccessGuardService> guard) {
        this.registry = registry;
        this.zustand = zustand;
        this.guard = guard;
    }

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
    public int order() {
        return 900;
    }

    /** The roles of the watch settings — never the user's own switch (it is not in the list). */
    @Override
    public boolean available() {
        return WatchUhrZugriff.erlaubt(guard);
    }

    /** Outside the rotation: reachable by its address and by the long press, never by swiping. */
    @Override
    public boolean imUmlauf() {
        return false;
    }

    // ── Beschreibung ──────────────────────────────────────────────────────────────────────

    @Override
    public MobilSeite beschreibe() {
        List<MobilSeite.SchalterZeile> zeilen = new ArrayList<>();
        for (WatchPage s : schaltbare()) {
            zeilen.add(new MobilSeite.SchalterZeile(s.title(), SCHALTE, s.id(), zustand.seiteAktiv(s.id())));
        }
        return MobilSeite.neu()
                .schalter("Sichtbare Seiten", zeilen, HINWEIS)
                // ── Galerie: jeder Baustein einmal, mit Beispielwerten. Wer eine Seite baut,
                //    sieht hier auf dem Telefon, was er in MobilSeite beschreibt.
                .hinweis("Bausteine des Mobil-Frameworks (Beispiele, sie ändern nichts):")
                .wert("Wert", "14 g", "Zahl mit Beschriftung und Hinweis darunter")
                .kacheln(List.of(new MobilSeite.Kachel("Kachel", "1:45"),
                        new MobilSeite.Kachel("Kachel", "3")), "")
                .aktion(new MobilSeite.Aktion(new MobilSeite.Knopf(TIPPE, "Stop", "Stop"),
                        "Beispiel", null, MobilSeite.Farbe.STOP, 754L,
                        List.of(new MobilSeite.Knopf(TIPPE, "Nebenknopf", "−1"))))
                .knoepfe("Auswahl", List.of(
                        new MobilSeite.Knopf(TIPPE, "Büro", "Büro", true),
                        new MobilSeite.Knopf(TIPPE, "Kunde", "Kunde"),
                        new MobilSeite.Knopf(TIPPE, "Weg", "Weg")))
                .liste("Liste", "Keine Einträge.", List.of(
                        new MobilSeite.Eintrag("1", "Eintrag mit Zeiten", "1:45", LOESCHE, null, null,
                                new MobilSeite.Aenderung(AENDERE, List.of(
                                        new MobilSeite.Feld("von", MobilSeite.FeldArt.ZEIT, "08:00", "von"),
                                        new MobilSeite.Feld("bis", MobilSeite.FeldArt.ZEIT, "09:45", "bis")))),
                        new MobilSeite.Eintrag("2", "Termin", null, null, "10:30", "Ort darunter", null)))
                .bauen();
    }

    // ── Aktionen ──────────────────────────────────────────────────────────────────────────

    @Override
    public MobilAntwort handle(String aktion, String wert) {
        return handle(aktion, wert, Map.of());
    }

    @Override
    public MobilAntwort handle(String aktion, String wert, Map<String, String> felder) {
        return switch (aktion) {
            case SCHALTE -> schalte(wert);
            case TIPPE -> MobilAntwort.ok("Getippt: " + (wert == null ? "" : wert));
            case AENDERE -> MobilAntwort.ok("Übernommen: " + felder.getOrDefault("von", "")
                    + "–" + felder.getOrDefault("bis", ""));
            case LOESCHE -> MobilAntwort.ok("Gelöscht (nur Beispiel)");
            default -> MobilAntwort.fehler("Unbekannte Aktion.");
        };
    }

    /**
     * Flips one page and writes it at once. The id comes from the request, so it must name a
     * page this user may switch — anything else is refused, nothing is written.
     */
    private MobilAntwort schalte(String seitenId) {
        Optional<WatchPage> seite = schaltbare().stream().filter(s -> s.id().equals(seitenId)).findFirst();
        if (seite.isEmpty()) {
            log.info("Watch: Schalter fuer unbekannte oder gesperrte Seite {} abgelehnt", seitenId);
            return MobilAntwort.fehler("Diese Seite lässt sich hier nicht schalten.");
        }
        boolean neu = !zustand.seiteAktiv(seitenId);
        zustand.setzeSeite(seitenId, neu);
        return MobilAntwort.ok(seite.get().title() + (neu ? " an" : " aus"));
    }

    /**
     * Every page that may be switched: in the rotation and allowed by its access rule. Left out
     * are pages the roles forbid anyway — a switch that changes nothing is worse than none — and
     * the overview itself, the only way back to these switches.
     */
    List<WatchPage> schaltbare() {
        WatchPageRegistry r = registry.getIfAvailable();
        if (r == null) {
            return List.of();
        }
        List<WatchPage> alle = new ArrayList<>();
        for (WatchPage s : r.alle()) {
            if (s.imUmlauf() && erlaubt(s)) {
                alle.add(s);
            }
        }
        return alle;
    }

    private static boolean erlaubt(WatchPage s) {
        try {
            return s.available();
        } catch (Exception e) {
            log.warn("Watch: Zugriffsregel von {} nicht auswertbar: {}", s.id(), e.toString());
            return false;
        }
    }
}

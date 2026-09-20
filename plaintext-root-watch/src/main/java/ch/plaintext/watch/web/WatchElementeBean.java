/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * State for the element gallery. Holds one value per control so the page can show what a tap
 * actually produced — a gallery of controls that swallow their input teaches nothing.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component("watchElementeBean")
@Scope("session")
@Slf4j
public class WatchElementeBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Labels are short on purpose: anything longer wraps on a 184 px display.
     *
     * <p>Named after its getter {@code getAuswahlWerte()} and not {@code AUSWAHL}: the field
     * {@code auswahl} right below holds the <em>chosen</em> value, and two names that differ only
     * in case are a reading trap (java:S1845, card 1273). The constant was renamed rather than the
     * field, because {@code #{watchElementeBean.auswahl}} is bound in {@code elemente.xhtml} three
     * times — renaming the field would have been a silent EL break.</p>
     */
    public static final List<String> AUSWAHL_WERTE = List.of("Büro", "Kunde", "Weg", "Pause");

    @Getter
    @Setter
    private String auswahl = "Büro";

    @Getter
    @Setter
    private boolean schalter;

    @Getter
    @Setter
    private int zahl = 15;

    @Getter
    @Setter
    private LocalTime zeit = LocalTime.of(8, 30);

    @Getter
    @Setter
    private LocalDate datum = LocalDate.now();

    @Getter
    @Setter
    private String text = "";

    @Getter
    private String letzteAktion = "—";

    @Getter
    private boolean bestaetigungOffen;

    public List<String> getAuswahlWerte() {
        return AUSWAHL_WERTE;
    }

    public void tippe(String was) {
        letzteAktion = was;
        FacesMessages.info("Getippt: " + was);
    }

    /** Step buttons instead of a slider: a slider cannot be hit reliably on a watch. */
    public void mehr() {
        zahl = Math.min(zahl + 15, 480);
        letzteAktion = "plus 15 → " + zahl;
    }

    public void weniger() {
        zahl = Math.max(zahl - 15, 0);
        letzteAktion = "minus 15 → " + zahl;
    }

    public void frageNach() {
        bestaetigungOffen = true;
        letzteAktion = "Rueckfrage offen";
    }

    public void bestaetige() {
        bestaetigungOffen = false;
        letzteAktion = "bestaetigt";
        FacesMessages.info("Ausgefuehrt");
    }

    public void brichAb() {
        bestaetigungOffen = false;
        letzteAktion = "abgebrochen";
    }

}

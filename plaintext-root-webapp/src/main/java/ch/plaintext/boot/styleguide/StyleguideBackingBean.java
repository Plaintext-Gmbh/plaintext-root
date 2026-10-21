/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.styleguide;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Karte 1548: die Beispieldaten des Styleguides ({@code styleguide.xhtml}).
 *
 * <p>Fest und ohne Datenbank: der Styleguide soll jeden Zustand zeigen — viele Zeilen (Paginator
 * ab Seite 2), wenige Zeilen (kein Paginator), keine Zeile (Leerzustand mit Weg) —, und das in
 * jeder Anwendung und jedem Testlauf gleich. Zustandslos, deshalb ein gewoehnliches Singleton.</p>
 */
@Component("styleguideBean")
@ConditionalOnWebApplication
public class StyleguideBackingBean {

    /** Eine Beispielzeile. Ein Record: die Seite liest {@code #{row.kunde}} ueber {@code kunde()}. */
    public record Vertrag(String nummer, String kunde, String ort, String status, String statusArt,
                          BigDecimal betrag, LocalDate beginn) {
    }

    private static final String[] KUNDEN = {"Muster AG", "Bäckerei Zürcher", "Gärtnerei Öschberg",
            "Velo Huber", "Schreinerei Graf", "Treuhand Meier"};
    private static final String[] ORTE = {"Bern", "Zürich", "Burgdorf", "Thun", "Biel", "Luzern"};
    private static final String[][] STATUS = {{"aktiv", "ok"}, {"läuft aus", "warnung"},
            {"gekündigt", "fehler"}, {"Entwurf", "info"}, {"archiviert", ""}};

    private final List<Vertrag> viele = baue(27);
    private final List<Vertrag> wenige = baue(3);

    private static List<Vertrag> baue(int anzahl) {
        List<Vertrag> liste = new ArrayList<>();
        for (int i = 0; i < anzahl; i++) {
            String[] status = STATUS[i % STATUS.length];
            liste.add(new Vertrag(
                    String.format(java.util.Locale.ROOT, "V-%04d", 1001 + i),
                    KUNDEN[i % KUNDEN.length],
                    ORTE[(i * 5) % ORTE.length],
                    status[0], status[1],
                    BigDecimal.valueOf(1_250L + i * 7_311L % 98_765L, 2).multiply(BigDecimal.TEN),
                    LocalDate.of(2026, 1, 1).plusDays(i * 11L)));
        }
        return List.copyOf(liste);
    }

    public List<Vertrag> getViele() {
        return viele;
    }

    public List<Vertrag> getWenige() {
        return wenige;
    }

    public List<Vertrag> getKeine() {
        return List.of();
    }

    /** Die Hauptaktion des Beispiel-Seitenkopfs: meldet ueber FacesMessages an das eine Growl. */
    public void neu() {
        melde("Neuer Vertrag", "So meldet eine Seite: über FacesMessages an das eine Growl im Template.");
    }

    /** Eine seltene Aktion aus dem «⋯»-Menü. */
    public void exportieren() {
        melde("Export", "Seltenes steht im «⋯»-Menü, nicht als zweiter Knopf im Seitenkopf.");
    }

    /** Eine Zeilenaktion. */
    public void bearbeiten(Vertrag vertrag) {
        melde("Bearbeiten", vertrag.nummer() + " – Zeilenaktionen stehen in der Zeile.");
    }

    /** Die Löschen-Zeilenaktion (pt:deleteButton samt Rückfrage aus dem Template). */
    public void loeschen(Vertrag vertrag) {
        melde("Gelöscht", vertrag.nummer() + " – im Styleguide wird nichts gelöscht.");
    }

    private static void melde(String titel, String text) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, titel, text));
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude.web;

import ch.plaintext.claude.ClaudeAnfrage;
import ch.plaintext.claude.ClaudeAntwort;
import ch.plaintext.claude.ClaudeException;
import ch.plaintext.claude.service.AnthropicClaudeClient;
import ch.plaintext.claude.service.ClaudeNutzung;
import ch.plaintext.claude.service.ClaudeNutzungSpeicher;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Backing bean of {@code claude-api.xhtml}. Request-scoped: all state worth keeping (last check,
 * rate limits, consumption) lives in the client and the database, so nothing has to be serialized.
 * The key itself is never exposed — the page only learns whether one is set.
 */
@Component("claudeMonitorBean")
@Scope("request")
public class ClaudeMonitorBean {

    static final String QUELLE = "root-monitoring";
    static final String TESTFRAGE = "Antworte mit genau einem Wort: Funktioniert die Verbindung?";

    private final AnthropicClaudeClient client;
    private final ClaudeNutzungSpeicher speicher;

    private List<Sicht> sichten;
    private String testAntwort;
    private AnthropicClaudeClient.Kosten kosten;
    private String kostenFehler;

    public ClaudeMonitorBean(AnthropicClaudeClient client, ClaudeNutzungSpeicher speicher) {
        this.client = client;
        this.speicher = speicher;
    }

    /** Button "Jetzt prüfen". */
    public void pruefen() {
        client.pruefeVerbindung();
    }

    /** Button "Testfrage": a short prompt with max_tokens 20, counted like any other call. */
    public void testfrage() {
        try {
            ClaudeAntwort a = client.frage(new ClaudeAnfrage(QUELLE, null, null,
                    List.of(ClaudeAnfrage.Nachricht.benutzer(TESTFRAGE)), 20));
            testAntwort = a.modell() + ": „" + a.text().strip() + "“ (" + a.inputTokens() + " in / " + a.outputTokens()
                    + " out, " + a.stopGrund() + ")";
        } catch (ClaudeException e) {
            testAntwort = "Fehler " + e.art() + ": " + e.getMessage();
        }
        sichten = null;
    }

    /** Button "Kosten laden" — only on demand, the Admin API asks for at most one poll per minute. */
    public void ladeKosten() {
        try {
            kosten = client.kosten30Tage();
            kostenFehler = null;
        } catch (ClaudeException e) {
            kosten = null;
            kostenFehler = e.art() + ": " + e.getMessage();
        }
    }

    public boolean isKonfiguriert() {
        return client.isKonfiguriert();
    }

    public boolean isAdminKonfiguriert() {
        return client.isAdminKonfiguriert();
    }

    public String getDefaultModell() {
        return client.getDefaultModell();
    }

    public AnthropicClaudeClient.Verbindung getVerbindung() {
        return client.getLetztePruefung();
    }

    public AnthropicClaudeClient.RateLimitStand getRateLimit() {
        return client.getRateLimitStand();
    }

    /** One table of the consumption section. */
    public record Sicht(String titel, List<ClaudeNutzung.Summe> summen) {
    }

    /** Consumption by period, by calling module and by model. */
    public List<Sicht> getSichten() {
        if (sichten != null) {
            return sichten; // JSF evaluates the value several times per request
        }
        LocalDate heute = LocalDate.now();
        List<ClaudeNutzung.Zeile> z = speicher.ladeAb(heute.minusDays(30));
        sichten = List.of(new Sicht("Zeitraum", ClaudeNutzung.zeitraeume(z, heute)),
                new Sicht("Je Modul (30 Tage)", ClaudeNutzung.je(z, heute, ClaudeNutzung.Zeile::quelle)),
                new Sicht("Je Modell (30 Tage)", ClaudeNutzung.je(z, heute, ClaudeNutzung.Zeile::modell)));
        return sichten;
    }

    public String getTestAntwort() {
        return testAntwort;
    }

    public AnthropicClaudeClient.Kosten getKosten() {
        return kosten;
    }

    public String getKostenFehler() {
        return kostenFehler;
    }
}

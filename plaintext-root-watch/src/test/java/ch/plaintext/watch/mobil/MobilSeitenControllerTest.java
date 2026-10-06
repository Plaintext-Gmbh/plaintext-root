/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.service.WatchStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Karte 1355: der Controller des Mobil-Frameworks — wer eine Seite sieht, wer eine Aktion
 * ausloesen darf, und was zurueckkommt.
 *
 * <p>Die Registry ist echt, nur der Zustandsdienst ist ein Mock: die Sichtbarkeitsregel
 * ({@code sichtbar} = Rollen UND eigener Schalter) soll hier genau so greifen wie im Betrieb,
 * und nicht als nachgebaute Annahme.</p>
 */
class MobilSeitenControllerTest {

    private WatchStateService zustand;
    private ZaehlSeite zaehler;
    private ZaehlSeite gesperrt;
    private MobilSeitenController controller;

    /** Eine Seite, die mitzaehlt, was mit ihr geschieht. */
    static final class ZaehlSeite implements MobilWatchPage {
        final String id;
        final boolean erlaubt;
        final List<String> aufrufe = new ArrayList<>();
        Map<String, String> felder;
        RuntimeException wirft;

        ZaehlSeite(String id, boolean erlaubt) {
            this.id = id;
            this.erlaubt = erlaubt;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return "Zähler";
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public boolean available() {
            return erlaubt;
        }

        @Override
        public MobilSeite beschreibe() {
            return MobilSeite.neu().wert("Stand", String.valueOf(aufrufe.size()), null)
                    .knoepfe(null, List.of(new MobilSeite.Knopf("plus", "1", "+1"))).bauen();
        }

        @Override
        public MobilAntwort handle(String aktion, String wert) {
            if (wirft != null) {
                throw wirft;
            }
            aufrufe.add(aktion + ":" + wert);
            return MobilAntwort.ok("gezählt");
        }

        @Override
        public MobilAntwort handle(String aktion, String wert, Map<String, String> felder) {
            this.felder = felder;
            return handle(aktion, wert);
        }
    }

    static WatchPage facelet(String id, int order) {
        return new WatchPage() {
            public String id() {
                return id;
            }

            public String title() {
                return id;
            }

            public String view() {
                return "/watch/" + id + ".xhtml";
            }

            @Override
            public int order() {
                return order;
            }
        };
    }

    static WatchPage uebersicht() {
        return new WatchPage() {
            public String id() {
                return "elemente";
            }

            public String title() {
                return "Seiten";
            }

            public String view() {
                return "/watch/elemente.xhtml";
            }

            @Override
            public boolean imUmlauf() {
                return false;
            }
        };
    }

    @BeforeEach
    void aufbau() {
        zustand = mock(WatchStateService.class);
        when(zustand.seiteAktiv(anyString())).thenReturn(true);
        zaehler = new ZaehlSeite("zaehler", true);
        gesperrt = new ZaehlSeite("gesperrt", false);
        WatchPageRegistry registry = new WatchPageRegistry(
                List.of(facelet("home", 0), zaehler, gesperrt, facelet("zeit", 20), uebersicht()), zustand);
        controller = new MobilSeitenController(registry, zustand, new MobilDateien());
    }

    private static MockHttpServletRequest anfrage(boolean json) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setContextPath("/app");
        CsrfToken t = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok-1");
        r.setAttribute(CsrfToken.class.getName(), t);
        if (json) {
            r.addHeader("Accept", "application/json");
        }
        return r;
    }

    // ═══════════════════════════════════════════════════════════════ Anzeigen

    @Test
    @DisplayName("GET zeigt die Seite mit Rahmen, Position, Weiter und CSRF — und merkt sie sich")
    void zeigt() {
        ResponseEntity<String> r = controller.seite("zaehler", null, anfrage(false));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(r.getBody())
                .contains("<h1>Zähler</h1>")
                .contains("<span class=\"w-pos\">2/3</span>")          // home, zaehler, zeit
                .contains("href=\"/app/watch/zeit.html\"")               // die naechste, als .html
                .contains("data-lang-ziel=\"/app/watch/elemente.html\"") // die Uebersicht
                .contains("action=\"/app/watch/m/zaehler/plus\"")
                .contains("value=\"tok-1\"")
                .contains("/app/watch/m/_/watch.css?v=")
                .contains("/app/watch/m/_/mobil.js?v=");
        verify(zustand).merkeSeite("zaehler");
        // Die eigene, strengere CSP: kein unsafe-inline, kein unsafe-eval, kein fremder Host.
        String csp = r.getHeaders().getFirst("Content-Security-Policy");
        assertThat(csp).contains("script-src 'self';").contains("style-src 'self';")
                .doesNotContain("unsafe").doesNotContain("https:");
    }

    @Test
    @DisplayName("404 fuer unbekannte, gesperrte und Facelet-Seiten — ohne Unterschied; selbst abgeschaltete bleibt direkt aufrufbar")
    void nichtSichtbarIst404() {
        when(zustand.seiteAktiv("abgeschaltet")).thenReturn(false);
        ZaehlSeite abgeschaltet = new ZaehlSeite("abgeschaltet", true);
        controller = new MobilSeitenController(
                new WatchPageRegistry(List.of(zaehler, gesperrt, abgeschaltet, facelet("home", 0)), zustand),
                zustand, new MobilDateien());

        for (String id : List.of("gibtsnicht", "gesperrt", "home")) {
            ResponseEntity<String> r = controller.seite(id, null, anfrage(false));
            assertThat(r.getStatusCode()).as(id).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(r.getBody()).as(id).isEqualTo(MobilSeitenController.NICHT_GEFUNDEN);
        }
        verify(zustand, never()).merkeSeite(anyString());
        // Positivkontrolle: dieselbe Registry zeigt die erlaubte Seite.
        assertThat(controller.seite("zaehler", null, anfrage(false)).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Nachbesserung 1355: der eigene Schalter nimmt die Seite nur aus der Runde — direkt aufgerufen
        // erscheint sie (Daniel hatte "alkohol" abgeschaltet und bekam auf /watch/m/alkohol 404).
        assertThat(controller.seite("abgeschaltet", null, anfrage(false)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ═══════════════════════════════════════════════════════════════ Aktionen

    @Test
    @DisplayName("POST mit JSON-Wunsch: Aktion laeuft, Antwort traegt neuen Inhalt und Meldung")
    void aktionJson() {
        ResponseEntity<?> r = controller.aktion("zaehler", "plus", "1", anfrage(true));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) r.getBody();
        assertThat(json).containsEntry("ok", true).containsEntry("meldung", "gezählt");
        // Der Inhalt ist NACH der Aktion beschrieben: der Stand ist schon 1.
        assertThat((String) json.get("inhalt")).contains("<div class=\"w-value\">1</div>");
        assertThat(zaehler.aufrufe).containsExactly("plus:1");
    }

    @Test
    @DisplayName("1387: Felder kommen nur als f-<name> an — nie _csrf, nie wert, Anzahl und Laenge begrenzt")
    void felder() {
        MockHttpServletRequest r = anfrage(true);
        r.addParameter("_csrf", "tok-1");
        r.addParameter("wert", "5");
        r.addParameter("f-von", "08:15");
        r.addParameter("f-bis", "09:00");
        r.addParameter("f-Gross", "x");           // Name, den kein Feld haben darf
        r.addParameter("f-", "x");
        r.addParameter("von", "07:00");           // ohne Praefix
        r.addParameter("f-text", "a".repeat(500));
        controller.aktion("zaehler", "plus", "5", r);

        assertThat(zaehler.felder).containsOnlyKeys("von", "bis", "text")
                .containsEntry("von", "08:15").containsEntry("bis", "09:00");
        assertThat(zaehler.felder.get("text")).hasSize(MobilSeitenController.FELD_LAENGE_MAX);

        MockHttpServletRequest viele = anfrage(true);
        for (int i = 0; i < 30; i++) {
            viele.addParameter("f-f" + i, "x");
        }
        controller.aktion("zaehler", "plus", "5", viele);
        assertThat(zaehler.felder).hasSize(MobilSeitenController.FELDER_MAX);

        // Positivkontrolle der Vorgabe: eine Seite ohne Felder bekommt die Aktion wie bisher.
        assertThat(zaehler.aufrufe).containsExactly("plus:5", "plus:5");
    }

    @Test
    @DisplayName("POST ohne JavaScript: 303 zurueck auf die Seite, die Meldung kommt beim naechsten GET")
    void aktionOhneJs() {
        MockHttpServletRequest post = anfrage(false);
        ResponseEntity<?> r = controller.aktion("zaehler", "plus", "1", post);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(r.getHeaders().getLocation()).hasToString("/app/watch/m/zaehler");

        MockHttpServletRequest get = anfrage(false);
        get.setSession(post.getSession());
        assertThat(controller.seite("zaehler", null, get).getBody()).contains(">gezählt</div>");
        // Einmal gezeigt ist gezeigt.
        assertThat(controller.seite("zaehler", null, get).getBody()).doesNotContain(">gezählt</div>");
    }

    @Test
    @DisplayName("Keine Aktion auf einer Seite, die der Aufrufer nicht sieht")
    void aktionAufGesperrterSeite() {
        ResponseEntity<?> r = controller.aktion("gesperrt", "plus", "1", anfrage(true));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gesperrt.aufrufe).isEmpty();
    }

    @Test
    @DisplayName("Aktionsnamen ausserhalb des Musters erreichen die Seite nie")
    void aktionsnameGeprueft() {
        for (String boese : List.of("Plus", "../x", "a b", "", "x".repeat(41))) {
            ResponseEntity<?> r = controller.aktion("zaehler", boese, "1", anfrage(true));
            assertThat(r.getStatusCode()).as(boese).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(zaehler.aufrufe).isEmpty();
    }

    @Test
    @DisplayName("Eine Ausnahme der Seite wird zur Meldung, nicht zur Fehlerseite")
    void ausnahmeWirdMeldung() {
        zaehler.wirft = new IllegalStateException("kaputt");
        ResponseEntity<?> r = controller.aktion("zaehler", "plus", "1", anfrage(true));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) r.getBody();
        assertThat(json).containsEntry("ok", false).containsEntry("meldung", MobilSeitenController.FEHLGESCHLAGEN);
    }

    // ═══════════════════════════════════════════════════════════════ Dateien

    @Test
    @DisplayName("Dateien: ein Jahr unter der richtigen Marke, no-cache unter einer falschen, 404 sonst")
    void dateien() {
        MobilDateien d = new MobilDateien();
        String marke = d.datei("mobil.js").orElseThrow().marke();

        ResponseEntity<byte[]> richtig = controller.datei("mobil.js", marke);
        assertThat(richtig.getHeaders().getCacheControl()).contains("max-age=31536000").contains("immutable");
        assertThat(richtig.getHeaders().getFirst("Content-Type")).startsWith("text/javascript");

        ResponseEntity<byte[]> alt = controller.datei("mobil.js", "000000");
        assertThat(alt.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(alt.getBody()).isEqualTo(richtig.getBody());

        assertThat(controller.datei("watch.css", null).getHeaders().getFirst("Content-Type")).startsWith("text/css");
        assertThat(controller.datei("../application.yml", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Die Seite zeigt ihre Adresse ueber view(), damit Weiter/Start/Handy-Link sie finden")
    void viewIstDieFrameworkAdresse() {
        assertThat(zaehler.view()).isEqualTo("/watch/m/zaehler");
        // WatchFrameBean und WatchStartController machen .xhtml -> .html; an dieser Adresse
        // darf das nichts aendern.
        assertThat(zaehler.view().replace(".xhtml", ".html")).isEqualTo("/watch/m/zaehler");
    }
}

/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.service.WatchStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Card 1289: the entry point sends the caller to the page they were last on.
 *
 * <p>Every statement here has its counter-check in the same class, because a redirect that
 * always went to the same place would be green on half of them: the remembered page is asked
 * for <b>and</b> a different remembered page produces a different target, and the fallback is
 * asked for <b>and</b> proved not to be what a remembered page yields.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchStartControllerTest {

    private WatchStateService zustand;

    private static WatchPage seite(String id, int order, boolean imUmlauf) {
        return new WatchPage() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String title() {
                return "Titel " + id;
            }

            @Override
            public String view() {
                return "/watch/" + id + ".xhtml";
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public boolean imUmlauf() {
                return imUmlauf;
            }
        };
    }

    @BeforeEach
    void setUp() {
        zustand = mock(WatchStateService.class);
        when(zustand.seiteAktiv("home")).thenReturn(true);
        when(zustand.seiteAktiv("zeit")).thenReturn(true);
        when(zustand.seiteAktiv("elemente")).thenReturn(true);
    }

    private WatchPageRegistry registry() {
        return new WatchPageRegistry(
                List.of(seite("home", 0, true), seite("zeit", 10, true),
                        seite("elemente", 900, false)),
                zustand);
    }

    private String ziel(WatchPageRegistry registry) throws IOException {
        WatchStartController controller = new WatchStartController(registry, zustand);
        MockHttpServletRequest anfrage = new MockHttpServletRequest("GET", WatchStartController.PFAD);
        MockHttpServletResponse antwort = new MockHttpServletResponse();
        controller.start(anfrage, antwort);
        return antwort.getRedirectedUrl();
    }

    @Test
    @DisplayName("Der Start landet auf der zuletzt offenen Seite")
    void startLandetAufDerGemerktenSeite() throws IOException {
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.of("zeit"));

        assertEquals("/watch/zeit.html", ziel(registry()));
    }

    @Test
    @DisplayName("GEGENPROBE: ohne gemerkte Seite landet derselbe Aufruf auf der ersten")
    void ohneGemerkteSeiteAufDieErste() throws IOException {
        // Ohne diese Messung belegt der Test oben nichts: eine Weiterleitung, die IMMER auf
        // /watch/zeit.html ginge, waere dort ebenfalls gruen.
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.empty());

        assertEquals("/watch/home.html", ziel(registry()));
    }

    @Test
    @DisplayName("Auch eine Seite ausserhalb des Umlaufs wird wieder geoeffnet")
    void auchAusserhalbDesUmlaufs() throws IOException {
        // Die Uebersicht laeuft seit Karte 1260 nicht im Umlauf mit, ist aber ueber ihre
        // Adresse erwuenscht — und wer sie zuletzt offen hatte, will sie beim Start wieder.
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.of("elemente"));

        assertEquals("/watch/elemente.html", ziel(registry()));
    }

    @Test
    @DisplayName("Eine abgeschaltete gemerkte Seite faellt auf die erste sichtbare zurueck")
    void abgeschalteteGemerkteSeiteFaelltZurueck() throws IOException {
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.of("zeit"));
        when(zustand.seiteAktiv("zeit")).thenReturn(false);

        String ziel = ziel(registry());

        assertEquals("/watch/home.html", ziel);
        assertNotEquals("/watch/zeit.html", ziel,
                "sonst schickt der Einstieg auf eine Seite, von der der Rahmen sofort wieder "
                        + "wegleitet — zwei Umleitungen fuer nichts");
    }

    @Test
    @DisplayName("Ist keine Seite sichtbar, bleibt der Rueckfall auf die Uebersicht")
    void ohneSichtbareSeiteRueckfall() throws IOException {
        assertEquals(WatchStartController.RUECKFALL,
                ziel(new WatchPageRegistry(List.of(), zustand)));
    }

    @Test
    @DisplayName("Der Kontextpfad steht vor der Adresse")
    void kontextpfadWirdVorangestellt() throws IOException {
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.of("zeit"));
        WatchStartController controller = new WatchStartController(registry(), zustand);
        MockHttpServletRequest anfrage = new MockHttpServletRequest("GET", WatchStartController.PFAD);
        anfrage.setContextPath("/app");
        MockHttpServletResponse antwort = new MockHttpServletResponse();

        controller.start(anfrage, antwort);

        assertEquals("/app/watch/zeit.html", antwort.getRedirectedUrl());
    }

    @Test
    @DisplayName("Der Einstieg selbst nennt keine Seite — sonst friert ihn jedes Lesezeichen ein")
    void einstiegNenntKeineSeite() {
        // Die Adresse landet auf Home-Bildschirmen und in gespeicherten Links. Endete sie auf
        // .html, waere sie eine JSF-Sicht: der FacesServlet haengt an *.html
        // (joinfaces.faces-servlet.url-mappings) und HtmlToXhtmlRewriteFilter leitete sie auf
        // eine Facelet-Datei weiter, die es nicht gibt — der Controller liefe nie.
        assertEquals("/watch/start", WatchStartController.PFAD);
    }
}

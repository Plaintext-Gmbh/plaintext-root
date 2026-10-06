/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Karte 1387: alte Adressen einer umgestellten Watch-Seite fuehren auf die neue — und nur die.
 *
 * <p>Jede Umleitung hat ihre Gegenprobe: eine Facelet-Seite, die noch nicht umgestellt ist,
 * eine fremde Adresse unter {@code /watch/} und die neue Adresse selbst gehen unberuehrt durch.</p>
 */
class MobilAltadressenFilterTest {

    private MobilAltadressenFilter filter;

    static MobilWatchPage mobil(String id) {
        return new MobilWatchPage() {
            public String id() {
                return id;
            }

            public String title() {
                return id;
            }

            public MobilSeite beschreibe() {
                return MobilSeite.neu().bauen();
            }

            public MobilAntwort handle(String aktion, String wert) {
                return MobilAntwort.ok(null);
            }
        };
    }

    static WatchPage facelet(String id) {
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
        };
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<WatchPageRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(
                new WatchPageRegistry(List.of(mobil("zeit"), mobil("home"), facelet("kalender")), null));
        filter = new MobilAltadressenFilter(provider);
    }

    private record Lauf(MockHttpServletResponse antwort, boolean weitergereicht) {
    }

    private Lauf rufe(String methode, String kontext, String pfad) throws Exception {
        MockHttpServletRequest anfrage = new MockHttpServletRequest(methode, kontext + pfad);
        anfrage.setContextPath(kontext);
        anfrage.setQueryString("t=geheim");
        MockHttpServletResponse antwort = new MockHttpServletResponse();
        MockFilterChain kette = new MockFilterChain();
        filter.doFilter(anfrage, antwort, kette);
        return new Lauf(antwort, kette.getRequest() != null);
    }

    @Test
    @DisplayName(".html und .xhtml einer umgestellten Seite -> 302 auf /watch/m/<id>, ohne Abfrageteil")
    void leitetUm() throws Exception {
        for (String alt : List.of("/watch/zeit.html", "/watch/zeit.xhtml")) {
            Lauf l = rufe("GET", "", alt);
            assertThat(l.weitergereicht()).as(alt).isFalse();
            assertThat(l.antwort().getStatus()).as(alt).isEqualTo(302);
            assertThat(l.antwort().getHeader("Location")).as(alt).isEqualTo("/watch/m/zeit");
            assertThat(l.antwort().getHeader("Cache-Control")).isEqualTo("no-store");
        }
        assertThat(rufe("GET", "", "/watch/home.html").antwort().getHeader("Location")).isEqualTo("/watch/m/home");
    }

    @Test
    @DisplayName("Kontextpfad bleibt vorne; ein POST der alten Seite wird 303 (kein zweites Absenden)")
    void kontextUndPost() throws Exception {
        Lauf get = rufe("GET", "/app", "/watch/zeit.html");
        assertThat(get.antwort().getHeader("Location")).isEqualTo("/app/watch/m/zeit");

        Lauf post = rufe("POST", "", "/watch/zeit.html");
        assertThat(post.antwort().getStatus()).isEqualTo(303);
        assertThat(post.weitergereicht()).isFalse();
    }

    @Test
    @DisplayName("Gegenprobe: Facelet-Seite, fremde Adresse, neue Adresse, Einstellungen gehen unberuehrt durch")
    void gegenprobe() throws Exception {
        for (String pfad : List.of("/watch/kalender.html", "/watch/gibtsnicht.html", "/watch/m/zeit",
                "/watch/start", "/watch/watch.css", "/watch-einstellungen.html", "/zeit.html")) {
            Lauf l = rufe("GET", "", pfad);
            assertThat(l.weitergereicht()).as(pfad).isTrue();
            assertThat(l.antwort().getHeader("Location")).as(pfad).isNull();
        }
    }

    @Test
    @DisplayName("Ohne Registry (Modul ohne Seiten) geht alles durch statt zu werfen")
    @SuppressWarnings("unchecked")
    void ohneRegistry() throws Exception {
        ObjectProvider<WatchPageRegistry> leer = mock(ObjectProvider.class);
        filter = new MobilAltadressenFilter(leer);
        assertThat(rufe("GET", "", "/watch/zeit.html").weitergereicht()).isTrue();
    }

    @Test
    @DisplayName("Die Reihenfolge liegt vor dem .html-Rewrite (HIGHEST_PRECEDENCE + 30)")
    void reihenfolge() {
        assertThat(MobilAltadressenFilter.ORDER - org.springframework.core.Ordered.HIGHEST_PRECEDENCE).isLessThan(30);
    }
}

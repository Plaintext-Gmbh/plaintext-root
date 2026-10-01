/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import ch.plaintext.watch.mobil.MobilHtml;
import ch.plaintext.watch.mobil.MobilSeite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1387: die Startseite der Uhr als Seite des Mobil-Frameworks. Was {@code WatchHomeBeanTest}
 * fuer die Facelet-Fassung hielt, gilt hier weiter — vor allem, dass ein kaputtes Widget nicht die
 * ganze Seite mitnimmt.
 */
class WatchHomePageTest {

    private static WatchWidget widget(String id, String label, int order, Supplier<String> wert) {
        return new WatchWidget() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public String value() {
                return wert.get();
            }

            @Override
            public int order() {
                return order;
            }
        };
    }

    private static WatchHomePage mit(List<WatchWidget> widgets) {
        return new WatchHomePage(() -> widgets, null);
    }

    private static final MobilHtml.Formular F = new MobilHtml.Formular("/watch/m/home", "/watch/m/home/", null, null, null);

    @Test
    @DisplayName("Kennung, Platz und neue Adresse: home bleibt die erste Seite, jetzt unter /watch/m/")
    void kennung() {
        WatchHomePage p = mit(List.of());
        assertEquals("home", p.id());
        assertEquals(0, p.order());
        assertEquals("/watch/m/home", p.view());
        assertEquals("/watch/home.xhtml", p.frueheresView(), "die alte Adresse wird umgeleitet");
        assertTrue(p.available());
        assertTrue(p.imUmlauf());
    }

    @Test
    @DisplayName("Ohne jedes Widget zeigt die Startseite den Leertext statt zu werfen")
    void ohneWidgets() {
        assertTrue(mit(null).kacheln().isEmpty());
        String html = MobilHtml.inhalt(mit(List.of()).beschreibe(), F);
        assertTrue(html.contains(WatchHomePage.LEER.substring(0, 20)), html);
    }

    @Test
    @DisplayName("Die Kacheln stehen nach order, bei Gleichstand nach id")
    void reihenfolge() {
        List<MobilSeite.Kachel> k = mit(List.of(
                widget("b", "B", 10, () -> "2"),
                widget("a", "A", 10, () -> "1"),
                widget("z", "Z", 0, () -> "0"))).kacheln();

        assertEquals(List.of("Z", "A", "B"), k.stream().map(MobilSeite.Kachel::label).toList());
    }

    @Test
    @DisplayName("Ein Widget, dessen Wert wirft, faellt weg — die uebrigen bleiben stehen")
    void kaputtesWidgetFaelltWeg() {
        List<MobilSeite.Kachel> k = mit(List.of(
                widget("gut", "Gut", 0, () -> "42"),
                widget("kaputt", "Kaputt", 1, () -> {
                    throw new IllegalStateException("Absicht");
                }))).kacheln();

        assertEquals(List.of(new MobilSeite.Kachel("Gut", "42")), k);
    }

    @Test
    @DisplayName("Ein Widget, dessen Verfuegbarkeit wirft, gilt als nicht verfuegbar")
    void widgetMitKaputterVerfuegbarkeit() {
        WatchWidget kaputt = new WatchWidget() {
            @Override
            public String id() {
                return "x";
            }

            @Override
            public String label() {
                return "X";
            }

            @Override
            public String value() {
                return "1";
            }

            @Override
            public boolean available() {
                throw new IllegalStateException("Absicht");
            }
        };

        assertTrue(mit(List.of(kaputt)).kacheln().isEmpty());
    }

    @Test
    @DisplayName("Ab drei Kacheln drei Spalten, darunter zwei — wie home.xhtml")
    void spalten() {
        String zwei = MobilHtml.inhalt(mit(List.of(
                widget("a", "A", 0, () -> "1"),
                widget("b", "B", 1, () -> "2"))).beschreibe(), F);
        String drei = MobilHtml.inhalt(mit(List.of(
                widget("a", "A", 0, () -> "1"),
                widget("b", "B", 1, () -> "2"),
                widget("c", "C", 2, () -> "3"))).beschreibe(), F);

        assertTrue(zwei.contains("class=\"w-widgets\""), zwei);
        assertFalse(zwei.contains("w-3"), zwei);
        assertTrue(drei.contains("class=\"w-widgets w-3\""), drei);
    }

    @Test
    @DisplayName("Rollen wie bisher (Alias watch/home -> watch-einstellungen): der Waechter entscheidet, fail-closed")
    @SuppressWarnings("unchecked")
    void rollen() {
        org.springframework.beans.factory.ObjectProvider<ch.plaintext.boot.security.PageAccessGuardService> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        ch.plaintext.boot.security.PageAccessGuardService guard =
                org.mockito.Mockito.mock(ch.plaintext.boot.security.PageAccessGuardService.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(guard);
        WatchHomePage p = new WatchHomePage(List::of, provider);

        org.mockito.Mockito.when(guard.hasAccessToView("/watch-einstellungen.xhtml")).thenReturn(true);
        assertTrue(p.available(), "Positivkontrolle: mit den Rollen der Watch-Einstellungen sichtbar");
        org.mockito.Mockito.when(guard.hasAccessToView("/watch-einstellungen.xhtml")).thenReturn(false);
        assertFalse(p.available(), "ohne die Rollen nicht sichtbar");
        org.mockito.Mockito.when(guard.hasAccessToView(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("Absicht"));
        assertFalse(p.available(), "ein Waechter, der wirft, heisst nein");
    }

    @Test
    @DisplayName("Die Startseite hat keine eigene Aktion — ein erfundener Name aendert nichts")
    void keineAktion() {
        assertFalse(mit(List.of()).handle("weiter", null).ok());
    }
}

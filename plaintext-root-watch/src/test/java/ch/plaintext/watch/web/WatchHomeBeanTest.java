/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.watch.page.WatchWidget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The home screen has one job beyond listing: it must survive a broken widget. On a watch a
 * blank page leaves the user nothing to act on, while three of four tiles still do.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchHomeBeanTest {

    private static WatchWidget widget(String id, String label, int order, java.util.function.Supplier<String> wert) {
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

    private static WatchHomeBean mit(List<WatchWidget> widgets) {
        WatchHomeBean bean = new WatchHomeBean();
        ReflectionTestUtils.setField(bean, "alle", widgets);
        return bean;
    }

    @Test
    @DisplayName("Ohne jedes Widget bleibt die Startseite leer statt zu werfen")
    void ohneWidgets() {
        assertTrue(mit(null).getKacheln().isEmpty());
        assertTrue(mit(List.of()).getKacheln().isEmpty());
    }

    @Test
    @DisplayName("Die Kacheln stehen nach order, bei Gleichstand nach id")
    void reihenfolge() {
        List<WatchHomeBean.Kachel> k = mit(List.of(
                widget("b", "B", 10, () -> "2"),
                widget("a", "A", 10, () -> "1"),
                widget("z", "Z", 0, () -> "0"))).getKacheln();

        assertEquals(List.of("z", "a", "b"), k.stream().map(WatchHomeBean.Kachel::id).toList());
    }

    @Test
    @DisplayName("Ein Widget, dessen Wert wirft, faellt weg — die uebrigen bleiben stehen")
    void kaputtesWidgetFaelltWeg() {
        List<WatchHomeBean.Kachel> k = mit(List.of(
                widget("gut", "Gut", 0, () -> "42"),
                widget("kaputt", "Kaputt", 1, () -> {
                    throw new IllegalStateException("Absicht");
                }))).getKacheln();

        assertEquals(1, k.size());
        assertEquals("gut", k.getFirst().id());
        assertEquals("42", k.getFirst().value());
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

        assertTrue(mit(List.of(kaputt)).getKacheln().isEmpty());
    }

    @Test
    @DisplayName("Ab drei Kacheln wechselt die Reihe auf drei Spalten")
    void spaltenKlasse() {
        assertEquals("w-widgets", mit(List.of(
                widget("a", "A", 0, () -> "1"),
                widget("b", "B", 1, () -> "2"))).getSpaltenKlasse());

        assertEquals("w-widgets w-3", mit(List.of(
                widget("a", "A", 0, () -> "1"),
                widget("b", "B", 1, () -> "2"),
                widget("c", "C", 2, () -> "3"))).getSpaltenKlasse());
    }
}

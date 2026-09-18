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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outside a FacesContext the bean must still read and write the setting — everything that needs
 * the context degrades to a relative address instead of throwing.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchSettingsBeanTest {

    private WatchStateService zustand;
    private WatchSettingsBean bean;

    private static WatchPage seite(String id, int order) {
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
        };
    }

    @BeforeEach
    void setUp() {
        zustand = mock(WatchStateService.class);
        bean = new WatchSettingsBean();
        ReflectionTestUtils.setField(bean, "zustand", zustand);
        ReflectionTestUtils.setField(bean, "registry",
                new WatchPageRegistry(List.of(seite("home", 0), seite("zeit", 10))));
    }

    @Test
    @DisplayName("Der Seitenaufruf liest den gespeicherten Schalter")
    void seitenaufrufLiestSchalter() {
        when(zustand.testseiteAktiv()).thenReturn(true);

        bean.seitenaufruf();

        assertTrue(bean.isTestseiteAktiv());
    }

    @Test
    @DisplayName("Speichern reicht den Schalter an den Dienst weiter")
    void speichernReichtWeiter() {
        bean.setTestseiteAktiv(true);
        bean.speichern();
        verify(zustand).setzeTestseite(true);

        bean.setTestseiteAktiv(false);
        bean.speichern();
        verify(zustand).setzeTestseite(false);
    }

    @Test
    @DisplayName("Ohne FacesContext liefert die Adresse den relativen Pfad statt zu werfen")
    void adresseOhneKontext() {
        assertEquals("/watch/home.html", bean.getWatchAdresse());
    }

    @Test
    @DisplayName("Die Seitenliste kommt aus dem Register")
    void seitenliste() {
        assertEquals(List.of("home", "zeit"), bean.getSeiten().stream().map(WatchPage::id).toList());
    }

    @Test
    @DisplayName("Der Schalter steht ohne Aufruf auf aus — kein stiller Vorgabewert 'an'")
    void schalterStehtAus() {
        assertFalse(bean.isTestseiteAktiv());
    }
}

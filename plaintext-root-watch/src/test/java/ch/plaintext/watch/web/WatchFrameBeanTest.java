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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checked here without a FacesContext: outside a request the bean must still move and still
 * remember. Everything that needs the context (the redirect) is a no-op then, and that is the
 * point — a unit test that had to boot JSF would not be run.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchFrameBeanTest {

    private WatchStateService zustand;
    private WatchPageRegistry registry;
    private WatchFrameBean bean;

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
        registry = new WatchPageRegistry(List.of(seite("home", 0), seite("zeit", 10), seite("test", 20)), null);
        bean = new WatchFrameBean();
        ReflectionTestUtils.setField(bean, "registry", registry);
        ReflectionTestUtils.setField(bean, "zustand", zustand);
    }

    @Test
    @DisplayName("Der Seitenaufruf holt die gemerkte Seite und bestaetigt sie")
    void seitenaufrufHoltGemerkteSeite() {
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.of("zeit"));

        bean.seitenaufruf();

        assertEquals("zeit", bean.getAktuelle().id());
        verify(zustand).merkeSeite("zeit");
    }

    @Test
    @DisplayName("Liefert der Dienst keine Seite, bleibt die Anzeige leer statt zu werfen")
    void seitenaufrufOhneSeite() {
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(bean, "registry", new WatchPageRegistry(List.of(), null));

        bean.seitenaufruf();

        assertEquals(null, bean.getAktuelle());
        assertEquals("Watch", bean.getTitel());
        assertEquals("", bean.getPosition());
        verify(zustand, never()).merkeSeite(anyString());
    }

    @Test
    @DisplayName("weiter() rueckt eine Seite vor und merkt sie")
    void weiterRuecktVor() {
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.of("home"));
        bean.seitenaufruf();

        bean.weiter();

        assertEquals("zeit", bean.getAktuelle().id());
        verify(zustand).merkeSeite("zeit");
    }

    @Test
    @DisplayName("zurueck() von der ersten Seite laeuft auf die letzte um")
    void zurueckLaeuftUm() {
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.of("home"));
        bean.seitenaufruf();

        bean.zurueck();

        assertEquals("test", bean.getAktuelle().id());
        verify(zustand).merkeSeite("test");
    }

    @Test
    @DisplayName("Die Positionsanzeige zaehlt ab eins")
    void positionZaehltAbEins() {
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.of("zeit"));
        bean.seitenaufruf();

        assertEquals("2/3", bean.getPosition());
        assertEquals("Titel zeit", bean.getTitel());
    }

    @Test
    @DisplayName("Eine abgeschaltete Seite wird nicht gezeigt, auch nicht bei Direktaufruf")
    void abgeschalteteSeiteNichtDirektErreichbar() {
        // Ohne FacesContext kann der Direktaufruf nicht nachgestellt werden; geprueft wird
        // die Regel dahinter: was nicht verfuegbar ist, wird nicht zur aktuellen Seite.
        WatchPage aus = new WatchPage() {
            @Override
            public String id() {
                return "aus";
            }

            @Override
            public String title() {
                return "Aus";
            }

            @Override
            public String view() {
                return "/watch/aus.xhtml";
            }

            @Override
            public int order() {
                return 50;
            }

            @Override
            public boolean available() {
                return false;
            }
        };
        ReflectionTestUtils.setField(bean, "registry",
                new WatchPageRegistry(List.of(seite("home", 0), aus), null));
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.of("aus"));

        bean.seitenaufruf();

        assertEquals("home", bean.getAktuelle().id(),
                "Eine abgeschaltete Seite darf nicht die aktuelle werden");
        assertTrue(bean.getSeiten().stream().noneMatch(p -> "aus".equals(p.id())),
                "Sie gehoert auch nicht in die Seitenliste");
    }

    @Test
    @DisplayName("Ohne verfuegbare Seiten bewegt sich nichts und es wird nichts gemerkt")
    void ohneSeitenKeineBewegung() {
        ReflectionTestUtils.setField(bean, "registry", new WatchPageRegistry(List.of(), null));

        bean.weiter();
        bean.zurueck();

        assertEquals(null, bean.getAktuelle());
        assertTrue(bean.getSeiten().isEmpty());
        verify(zustand, never()).merkeSeite(anyString());
    }

    // ── Die CSP-Nonce (Karte 1256) ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ohne FacesContext liefert die Nonce leer statt zu werfen")
    void nonceOhneKontext() {
        // Genau der Fall, der die Seite sonst mitreissen wuerde: ausserhalb eines Requests.
        assertEquals("", bean.getCspNonce());
    }

    @Test
    @DisplayName("Die Nonce kommt aus der View-Map — unter dem Schluessel, den PrimeFaces liest")
    void nonceAusDerViewMap() {
        jakarta.faces.context.FacesContext fc = mock(jakarta.faces.context.FacesContext.class);
        jakarta.faces.component.UIViewRoot root = mock(jakarta.faces.component.UIViewRoot.class);
        when(fc.getViewRoot()).thenReturn(root);
        when(root.getViewMap(false)).thenReturn(
                new java.util.HashMap<>(java.util.Map.of(WatchFrameBean.NONCE_SCHLUESSEL, "abc123")));

        try (org.mockito.MockedStatic<jakarta.faces.context.FacesContext> statisch =
                     org.mockito.Mockito.mockStatic(jakarta.faces.context.FacesContext.class)) {
            statisch.when(jakarta.faces.context.FacesContext::getCurrentInstance).thenReturn(fc);

            assertEquals("abc123", bean.getCspNonce());
        }
    }

    @Test
    @DisplayName("Eine leere View-Map gibt den leeren String, keinen NPE")
    void nonceLeereViewMap() {
        jakarta.faces.context.FacesContext fc = mock(jakarta.faces.context.FacesContext.class);
        jakarta.faces.component.UIViewRoot root = mock(jakarta.faces.component.UIViewRoot.class);
        when(fc.getViewRoot()).thenReturn(root);
        when(root.getViewMap(false)).thenReturn(null);

        try (org.mockito.MockedStatic<jakarta.faces.context.FacesContext> statisch =
                     org.mockito.Mockito.mockStatic(jakarta.faces.context.FacesContext.class)) {
            statisch.when(jakarta.faces.context.FacesContext::getCurrentInstance).thenReturn(fc);

            assertEquals("", bean.getCspNonce());
        }
    }

    @Test
    @DisplayName("Der Schluessel ist der von PrimeFaces, nicht ein selbst ausgedachter")
    void schluessel() {
        // Weicht er ab, bleibt das Feld leer und jeder Knopfdruck gibt wieder eine
        // Whitelabel-Seite — ohne dass irgendetwas rot wuerde.
        assertEquals("primefaces.nonce", WatchFrameBean.NONCE_SCHLUESSEL);
    }
}

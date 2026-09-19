/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.service.WatchStateService;
import jakarta.faces.component.UIViewRoot;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cards 1257/1260: "switched off" has to mean "not there" — including when the address is
 * typed by hand.
 *
 * <h2>What went wrong before and must not come back</h2>
 *
 * <p>Measured on PROD (card, PR #219): {@code /watch/elemente.html} answered 200 with the full
 * page while the user's setting had it off, because {@code available()} only steered the
 * navigation. The guard in {@code WatchFrameBean.seitenaufruf()} closed that for the one switch
 * that existed then. This test pins that it also holds for the <b>new</b> per-user selection —
 * the second place that can switch a page off, and the one a rework is most likely to
 * forget.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchDirektaufrufTest {

    private WatchStateService zustand;
    private WatchFrameBean bean;
    private MockedStatic<FacesContext> statisch;
    private FacesContext fc;

    private static WatchPage seite(String id, int order) {
        return new WatchPage() {
            public String id() { return id; }
            public String title() { return id; }
            public String view() { return "/watch/" + id + ".xhtml"; }
            public int order() { return order; }
        };
    }

    @BeforeEach
    void setUp() {
        zustand = mock(WatchStateService.class);
        bean = new WatchFrameBean();
        ReflectionTestUtils.setField(bean, "zustand", zustand);

        fc = mock(FacesContext.class);
        UIViewRoot root = mock(UIViewRoot.class);
        when(fc.getViewRoot()).thenReturn(root);
        when(root.getViewId()).thenReturn("/watch/zeit.xhtml");
        when(fc.isPostback()).thenReturn(false);
        ExternalContext ext = mock(ExternalContext.class);
        when(fc.getExternalContext()).thenReturn(ext);
        when(ext.getRequestContextPath()).thenReturn("");

        statisch = Mockito.mockStatic(FacesContext.class);
        statisch.when(FacesContext::getCurrentInstance).thenReturn(fc);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        statisch.close();
    }

    private void mitAuswahl(boolean zeitAn) {
        when(zustand.seiteAktiv("home")).thenReturn(true);
        when(zustand.seiteAktiv("zeit")).thenReturn(zeitAn);
        ReflectionTestUtils.setField(bean, "registry",
                new WatchPageRegistry(List.of(seite("home", 0), seite("zeit", 10)), zustand));
        when(zustand.gemerkteSeitenId()).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Der Direktaufruf einer abgeschalteten Seite landet auf der ersten erlaubten")
    void direktaufrufAbgeschaltet() {
        mitAuswahl(false);

        bean.seitenaufruf();

        assertEquals("home", bean.getAktuelle().id(),
                "eine per Auswahl abgeschaltete Seite darf ueber ihre Adresse nicht erreichbar sein");
    }

    @Test
    @DisplayName("GEGENPROBE: dieselbe Adresse zeigt die Seite, sobald sie eingeschaltet ist")
    void gegenprobeEingeschaltet() {
        // Ohne diese Messung belegt der Test oben nichts — eine Umleitung, die IMMER auf die
        // erste Seite geht, waere dort ebenfalls gruen.
        mitAuswahl(true);

        bean.seitenaufruf();

        assertEquals("zeit", bean.getAktuelle().id());
    }
}

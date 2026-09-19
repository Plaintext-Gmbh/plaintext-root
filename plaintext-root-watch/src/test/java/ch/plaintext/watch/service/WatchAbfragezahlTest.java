/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.service;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.watch.entity.WatchUserState;
import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.repository.WatchUserStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card 1273: how many database queries one render costs, and that a read never writes.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>{@code WatchPageRegistry.sichtbar} asks {@code seiteAktiv(id)} for every page, and it is
 * evaluated on every render (deliberately not cached — a page switched off mid-session has to
 * disappear at once). Before this card every one of those questions went through
 * {@code eigenerZustand()} straight to the repository: with eight watch pages that was
 * <b>eight queries per render</b>, and the same again in the loops of
 * {@code WatchUebersichtBean} and {@code WatchSettingsBean}.
 *
 * <p>The number is measured here rather than argued about, and it is measured at the place
 * where it is decided: the calls arriving at {@link WatchUserStateRepository}. That is the
 * honest unit for this question — one repository lookup is one {@code SELECT}, because
 * {@code findByBenutzerAndDeletedFalse} is a derived query without a cache of its own.
 *
 * <h2>The second half: a read must not write</h2>
 *
 * <p>{@code abgeschalteteSeiten()}, {@code handyLinkAktiv()} and {@code handyLinkErstellt()}
 * are queries by name and created a row on first call ({@code repository.save(neu)} inside
 * {@code eigenerZustand()}). That — not the missing annotation — was the actual defect behind
 * the S2229/S6809 findings of card 1273.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchAbfragezahlTest {

    /** As many pages as the watch has today; the point of the measurement is that it does not matter. */
    private static final int SEITEN = 8;

    private WatchUserStateRepository repository;
    private WatchStateService service;
    private WatchPageRegistry registry;
    private MockedStatic<PlaintextSecurityHolder> sicherheit;

    private static WatchPage seite(String id, int order) {
        return new WatchPage() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String title() {
                return id;
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
            public boolean available() {
                return true;
            }
        };
    }

    @BeforeEach
    void setUp() {
        repository = mock(WatchUserStateRepository.class);
        service = new WatchStateService(repository);
        List<WatchPage> seiten = IntStream.range(0, SEITEN)
                .mapToObj(i -> seite("seite" + i, i * 10)).toList();
        registry = new WatchPageRegistry(new ArrayList<>(seiten), service);

        sicherheit = Mockito.mockStatic(PlaintextSecurityHolder.class);
        sicherheit.when(PlaintextSecurityHolder::getUser).thenReturn("daniel@plaintext.ch");
        sicherheit.when(PlaintextSecurityHolder::getMandat).thenReturn("plaintext");
        when(repository.save(any(WatchUserState.class))).thenAnswer(a -> a.getArgument(0));

        // A request context, because that is the lifetime the cache is bound to. Without one the
        // service falls back to asking every time — deliberately, see WatchStateService.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        sicherheit.close();
    }

    private long abfragen() {
        return mockingDetails(repository).getInvocations().stream()
                .filter(i -> "findByBenutzerAndDeletedFalse".equals(i.getMethod().getName()))
                .count();
    }

    @Test
    @DisplayName("Ein Rendern ueber acht Seiten kostet EINE Abfrage, nicht eine je Seite")
    void einRenderEineAbfrage() {
        WatchUserState zustand = new WatchUserState();
        zustand.setBenutzer("daniel@plaintext.ch");
        when(repository.findByBenutzerAndDeletedFalse(anyString())).thenReturn(Optional.of(zustand));

        List<WatchPage> sichtbar = registry.verfuegbare();

        assertEquals(SEITEN, sichtbar.size(), "alle acht Seiten sind an — sonst misst der Test das Falsche");
        assertEquals(1, abfragen(),
                () -> "Ein Seitenaufruf darf den Zustand einmal laden, nicht je Seite. Gemessen: "
                        + abfragen() + " Abfragen fuer " + SEITEN + " Seiten.");
    }

    @Test
    @DisplayName("Positivkontrolle: ohne Anfragekontext faellt der Dienst auf eine Abfrage je Seite zurueck")
    void ohneAnfragekontextKeinZwischenspeicher() {
        RequestContextHolder.resetRequestAttributes();
        WatchUserState zustand = new WatchUserState();
        zustand.setBenutzer("daniel@plaintext.ch");
        when(repository.findByBenutzerAndDeletedFalse(anyString())).thenReturn(Optional.of(zustand));

        registry.verfuegbare();

        // This is what makes the number above meaningful: the saving really comes from the cache
        // and not from the registry quietly asking less often.
        assertEquals(SEITEN, abfragen(),
                "ohne Anfragekontext fragt der Dienst je Seite — sonst misst der Haupttest nichts");
    }

    @Test
    @DisplayName("Eine Lesemethode legt keine Zeile an")
    void lesemethodenSchreibenNicht() {
        when(repository.findByBenutzerAndDeletedFalse(anyString())).thenReturn(Optional.empty());

        assertTrue(service.abgeschalteteSeiten().isEmpty());
        assertTrue(service.seiteAktiv("seite0"), "ohne Zeile ist jede Seite an");
        assertTrue(service.handyLinkErstellt().isEmpty());
        assertFalse(service.handyLinkAktiv());

        verify(repository, never()).save(any());
    }
}

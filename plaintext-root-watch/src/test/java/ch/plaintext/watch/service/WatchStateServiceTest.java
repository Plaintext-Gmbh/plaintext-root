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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service decides two things that carry weight: who the state belongs to, and when a write
 * actually happens. Both are checked here against the repository, not against a database — the
 * mapping itself is covered by the reactor's contract tests.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchStateServiceTest {

    private WatchUserStateRepository repository;
    private WatchPageRegistry registry;
    private WatchStateService service;
    private MockedStatic<PlaintextSecurityHolder> sicherheit;

    private static WatchPage seite(String id, int order, boolean verfuegbar) {
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
                return verfuegbar;
            }
        };
    }

    @BeforeEach
    void setUp() {
        repository = mock(WatchUserStateRepository.class);
        registry = new WatchPageRegistry(List.of(seite("home", 0, true), seite("zeit", 10, true)));
        service = new WatchStateService(repository);
        sicherheit = Mockito.mockStatic(PlaintextSecurityHolder.class);
        sicherheit.when(PlaintextSecurityHolder::getUser).thenReturn("daniel@plaintext.ch");
        sicherheit.when(PlaintextSecurityHolder::getMandat).thenReturn("plaintext");
        when(repository.save(any(WatchUserState.class))).thenAnswer(a -> a.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        sicherheit.close();
    }

    @Test
    @DisplayName("Ohne angemeldeten Benutzer entsteht kein Zustand — und es wird nichts geschrieben")
    void ohneBenutzerKeinZustand() {
        sicherheit.when(PlaintextSecurityHolder::getUser).thenReturn(null);

        assertTrue(service.eigenerZustand().isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Ein leerer Benutzername zaehlt wie kein Benutzer")
    void leererBenutzerZaehltNicht() {
        sicherheit.when(PlaintextSecurityHolder::getUser).thenReturn("   ");

        assertTrue(service.eigenerZustand().isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Beim ersten Aufruf wird ein Zustand angelegt — mit Benutzer und Mandat aus dem Sicherheitskontext")
    void ersterAufrufLegtAn() {
        when(repository.findByBenutzerAndDeletedFalse("daniel@plaintext.ch")).thenReturn(Optional.empty());

        WatchUserState z = service.eigenerZustand().orElseThrow();

        assertEquals("daniel@plaintext.ch", z.getBenutzer());
        assertEquals("plaintext", z.getMandat());
        verify(repository, times(1)).save(any(WatchUserState.class));
    }

    @Test
    @DisplayName("Ein vorhandener Zustand wird gelesen, nicht neu angelegt")
    void vorhandenerZustandWirdGelesen() {
        WatchUserState vorhanden = new WatchUserState();
        vorhanden.setBenutzer("daniel@plaintext.ch");
        vorhanden.setAktuelleSeite("zeit");
        when(repository.findByBenutzerAndDeletedFalse("daniel@plaintext.ch")).thenReturn(Optional.of(vorhanden));

        assertEquals("zeit", service.eigenerZustand().orElseThrow().getAktuelleSeite());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("merkeSeite schreibt NUR bei echter Aenderung")
    void merkeSeiteSchreibtNurBeiAenderung() {
        WatchUserState vorhanden = new WatchUserState();
        vorhanden.setBenutzer("daniel@plaintext.ch");
        vorhanden.setAktuelleSeite("zeit");
        when(repository.findByBenutzerAndDeletedFalse("daniel@plaintext.ch")).thenReturn(Optional.of(vorhanden));

        service.merkeSeite("zeit");
        verify(repository, never()).save(any());

        service.merkeSeite("home");
        verify(repository, times(1)).save(vorhanden);
        assertEquals("home", vorhanden.getAktuelleSeite());
    }

    @Test
    @DisplayName("merkeSeite mit leerer Id tut nichts — und fragt nicht einmal die Datenbank")
    void merkeSeiteIgnoriertLeer() {
        service.merkeSeite(null);
        service.merkeSeite("  ");

        verify(repository, never()).findByBenutzerAndDeletedFalse(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Die gemerkte Seitenkennung wird herausgegeben")
    void gemerkteKennungWirdGeliefert() {
        WatchUserState vorhanden = new WatchUserState();
        vorhanden.setBenutzer("daniel@plaintext.ch");
        vorhanden.setAktuelleSeite("zeit");
        when(repository.findByBenutzerAndDeletedFalse("daniel@plaintext.ch")).thenReturn(Optional.of(vorhanden));

        assertEquals("zeit", service.gemerkteSeitenId().orElseThrow());
    }

    @Test
    @DisplayName("Ohne gemerkte Seite bleibt die Kennung leer — der Dienst raet keine")
    void ohneGemerkteSeite() {
        WatchUserState vorhanden = new WatchUserState();
        vorhanden.setBenutzer("daniel@plaintext.ch");
        when(repository.findByBenutzerAndDeletedFalse("daniel@plaintext.ch")).thenReturn(Optional.of(vorhanden));

        assertTrue(service.gemerkteSeitenId().isEmpty());
    }

    @Test
    @DisplayName("Der Dienst kennt das Seitenregister nicht — sonst schliesst sich der Bean-Kreis")
    void dienstKenntDasRegisterNicht() {
        // Keine Stilfrage: WatchTestPage fragt diesen Dienst nach ihrer Verfuegbarkeit, das
        // Register sammelt sie ein. Haette der Dienst das Register, startet die Anwendung nicht
        // mehr (BeanCurrentlyInCreationException, gemessen im root-Reaktor am 18.09.2026).
        boolean kenntRegister = java.util.Arrays.stream(WatchStateService.class.getDeclaredFields())
                .anyMatch(f -> WatchPageRegistry.class.isAssignableFrom(f.getType()));
        assertFalse(kenntRegister,
                "WatchStateService haelt ein Feld vom Typ WatchPageRegistry — damit ist der "
                        + "Bean-Kreis wieder da und die Anwendung startet nicht.");
    }

    @Test
    @DisplayName("Die Testseite laesst sich schalten und wird zurueckgelesen")
    void testseiteSchalten() {
        WatchUserState vorhanden = new WatchUserState();
        vorhanden.setBenutzer("daniel@plaintext.ch");
        when(repository.findByBenutzerAndDeletedFalse("daniel@plaintext.ch")).thenReturn(Optional.of(vorhanden));

        assertFalse(service.testseiteAktiv());
        service.setzeTestseite(true);
        assertTrue(service.testseiteAktiv());
        verify(repository, times(1)).save(vorhanden);
    }

    @Test
    @DisplayName("Ohne Benutzer meldet die Testseiten-Abfrage schlicht 'aus' statt zu werfen")
    void testseiteOhneBenutzer() {
        sicherheit.when(PlaintextSecurityHolder::getUser).thenReturn(null);

        assertFalse(service.testseiteAktiv());
        service.setzeTestseite(true);
        verify(repository, never()).save(any());
    }
}

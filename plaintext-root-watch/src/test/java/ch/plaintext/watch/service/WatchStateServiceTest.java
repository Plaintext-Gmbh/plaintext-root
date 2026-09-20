/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.service;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.watch.entity.WatchUserState;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.repository.WatchUserStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
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
    private WatchStateService service;
    private MockedStatic<PlaintextSecurityHolder> sicherheit;

    @BeforeEach
    void setUp() {
        repository = mock(WatchUserStateRepository.class);
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
    @DisplayName("Lesen legt nichts an — auch nicht beim allerersten Aufruf (Karte 1273)")
    void lesenLegtNichtsAn() {
        when(repository.findByBenutzerAndDeletedFalse("daniel@plaintext.ch")).thenReturn(Optional.empty());

        // Vorher legte genau dieser Aufruf eine Zeile an, und damit taten es auch
        // abgeschalteteSeiten(), handyLinkAktiv() und handyLinkErstellt() — Abfragen dem Namen
        // nach. Das war der Mangel hinter den S2229/S6809-Befunden, nicht die fehlende Annotation.
        assertTrue(service.eigenerZustand().isEmpty(), "eine Abfrage bringt keine Zeile in die Welt");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Der erste SCHREIBENDE Zugriff legt an — mit Benutzer und Mandat aus dem Sicherheitskontext")
    void ersterSchreibzugriffLegtAn() {
        when(repository.findByBenutzerAndDeletedFalse("daniel@plaintext.ch")).thenReturn(Optional.empty());

        service.merkeSeite("zeit");

        ArgumentCaptor<WatchUserState> gespeichert = ArgumentCaptor.forClass(WatchUserState.class);
        verify(repository, atLeastOnce()).save(gespeichert.capture());
        WatchUserState z = gespeichert.getValue();
        assertEquals("daniel@plaintext.ch", z.getBenutzer());
        assertEquals("plaintext", z.getMandat());
        assertEquals("zeit", z.getAktuelleSeite());
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

    // ---- Seitenauswahl je Benutzer (Karte 1257) ----------------------------------------------

    private WatchUserState vorhandenerZustand() {
        WatchUserState vorhanden = new WatchUserState();
        vorhanden.setBenutzer("daniel@plaintext.ch");
        when(repository.findByBenutzerAndDeletedFalse("daniel@plaintext.ch")).thenReturn(Optional.of(vorhanden));
        return vorhanden;
    }

    @Test
    @DisplayName("Ohne Eintrag gilt jede Seite als an — gespeichert wird die Negativliste")
    void ohneEintragAllesAn() {
        vorhandenerZustand();

        assertTrue(service.seiteAktiv("zeit"));
        assertTrue(service.seiteAktiv("gibtsnochnicht"),
                "eine Seite, die ein Modul morgen beisteuert, ist von sich aus sichtbar");
    }

    @Test
    @DisplayName("Eine abgeschaltete Seite wird zurueckgelesen, die anderen bleiben an")
    void seiteAbschalten() {
        WatchUserState z = vorhandenerZustand();

        service.setzeSeite("zeit", false);

        assertFalse(service.seiteAktiv("zeit"));
        assertTrue(service.seiteAktiv("home"), "nur die genannte Seite ist betroffen");
        assertEquals("zeit", z.getAbgeschalteteSeiten());
        verify(repository, times(1)).save(z);
    }

    @Test
    @DisplayName("Mehrere abgeschaltete Seiten stehen nebeneinander und gehen einzeln wieder an")
    void mehrereSeiten() {
        WatchUserState z = vorhandenerZustand();

        service.setzeSeite("zeit", false);
        service.setzeSeite("alkohol", false);
        assertEquals("zeit,alkohol", z.getAbgeschalteteSeiten());

        service.setzeSeite("zeit", true);
        assertEquals("alkohol", z.getAbgeschalteteSeiten());
        assertTrue(service.seiteAktiv("zeit"));
        assertFalse(service.seiteAktiv("alkohol"));
    }

    @Test
    @DisplayName("Ist die letzte Seite wieder an, steht null statt einer leeren Zeichenkette")
    void leereListeWirdNull() {
        WatchUserState z = vorhandenerZustand();
        service.setzeSeite("zeit", false);

        service.setzeSeite("zeit", true);

        assertEquals(null, z.getAbgeschalteteSeiten());
    }

    @Test
    @DisplayName("Ohne echte Aenderung wird nicht geschrieben")
    void keinSchreibenOhneAenderung() {
        WatchUserState z = vorhandenerZustand();

        service.setzeSeite("zeit", true);

        verify(repository, never()).save(z);
    }

    @Test
    @DisplayName("Ein Komma zu viel schaltet keine Seite mit leerem Namen ab")
    void stolperkomma() {
        WatchUserState z = vorhandenerZustand();
        z.setAbgeschalteteSeiten("zeit,,  ,alkohol");

        assertEquals(java.util.Set.of("zeit", "alkohol"), service.abgeschalteteSeiten());
        assertFalse(service.seiteAktiv(""), "eine leere Kennung ist nie eine Seite");
    }

    @Test
    @DisplayName("Ohne Benutzer meldet die Abfrage 'alles an' statt zu werfen — und schreibt nichts")
    void auswahlOhneBenutzer() {
        sicherheit.when(PlaintextSecurityHolder::getUser).thenReturn(null);

        assertTrue(service.seiteAktiv("zeit"));
        service.setzeSeite("zeit", false);
        verify(repository, never()).save(any());
    }

    // ---- Handy-Link (Karte 1257) --------------------------------------------------------------

    @Test
    @DisplayName("Ein gemerkter Handy-Link ist aktiv, traegt einen Zeitpunkt und die jti")
    void handyLinkMerken() {
        WatchUserState z = vorhandenerZustand();

        assertFalse(service.handyLinkAktiv());
        service.merkeHandyLink("jti-4711");

        assertTrue(service.handyLinkAktiv());
        assertEquals("jti-4711", z.getHandyLinkJti());
        assertTrue(service.handyLinkErstellt().isPresent());
    }

    @Test
    @DisplayName("Abschalten loescht Kennzeichen und Zeitpunkt mit — kein halber Zustand")
    void handyLinkAbschalten() {
        WatchUserState z = vorhandenerZustand();
        service.merkeHandyLink("jti-4711");

        service.schalteHandyLinkAb();

        assertFalse(service.handyLinkAktiv());
        assertEquals(null, z.getHandyLinkJti());
        assertTrue(service.handyLinkErstellt().isEmpty());
    }

    @Test
    @DisplayName("Die Abfrage fuer den Token-Pfad legt keine Zeile an")
    void handyLinkFuerFremdenBenutzerLegtNichtsAn() {
        when(repository.findByBenutzerAndDeletedFalse("jasmin@plaintext.ch")).thenReturn(Optional.empty());

        assertFalse(service.handyLinkAktivFuer("jasmin@plaintext.ch"));
        assertFalse(service.handyLinkAktivFuer(null));
        verify(repository, never()).save(any());
    }
}

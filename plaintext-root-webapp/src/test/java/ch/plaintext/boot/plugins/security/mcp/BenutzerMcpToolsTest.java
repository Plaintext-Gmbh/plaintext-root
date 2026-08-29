/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.security.mcp;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.model.UserMandate;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests fuer {@link BenutzerMcpTools}. Schwerpunkt ist nicht das Abbilden von Feldern, sondern das,
 * was der Import <b>nicht</b> tun darf: privilegierte Rollen aus einer Datei vergeben, den Mandanten
 * aus der Datei uebernehmen, ein bestehendes Konto ueberschreiben oder Geheimnisse ausgeben.
 */
class BenutzerMcpToolsTest {

    private final MyUserRepository userRepository = mock(MyUserRepository.class);
    private final UserMandateRepository mandateRepository = mock(UserMandateRepository.class);
    private final BenutzerMcpTools tools = new BenutzerMcpTools(userRepository, mandateRepository);

    private final PlaintextSecurity security = mock(PlaintextSecurity.class);

    @BeforeEach
    void setUp() {
        // PlaintextSecurityHolder haelt seinen Delegaten statisch; im Unit-Test wird er von Hand gesetzt.
        new PlaintextSecurityHolder().setDelegate(security);
        when(security.getMandat()).thenReturn("guild42");
        when(mandateRepository.findByMandatAndActiveTrue(anyString())).thenReturn(List.of());
        when(mandateRepository.findByUsernameAndActiveTrue(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Autorisierung ─────────────────────────────────────────────────────────────────────────

    @Test
    void ohneAuthentication_keinZugriff() {
        assertTrue(tools.listBenutzer().startsWith("FEHLER"));
        assertTrue(tools.exportBenutzer().startsWith("FEHLER"));
        assertTrue(tools.importBenutzer("{}").startsWith("FEHLER"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void ohneScopeAdmin_keinZugriff() {
        authentifiziereAls("ROLE_ADMIN", "SCOPE_READ", "SCOPE_WRITE");
        assertTrue(tools.exportBenutzer().contains("scope=ADMIN"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void ohneRolleAdminOderRoot_keinZugriff() {
        authentifiziereAls("ROLE_USER", "SCOPE_READ", "SCOPE_WRITE", "SCOPE_ADMIN");
        assertTrue(tools.exportBenutzer().contains("Rolle ADMIN oder ROOT"));
        verifyNoInteractions(userRepository);
    }

    // ── Export ────────────────────────────────────────────────────────────────────────────────

    @Test
    void exportTraegtKeineGeheimnisse() {
        authAdmin();
        MyUserEntity u = benutzer("s.m.butscher@gmail.com", "guild42");
        u.setPassword("$2a$10$sehrGeheimerHash");
        u.setTotpSecret("JBSWY3DPEHPK3PXP");
        u.setRecoveryCodes(Set.of("abcdef0123"));
        u.setOidcSubject("subject-123");
        when(userRepository.findAll()).thenReturn(List.of(u));

        String export = tools.exportBenutzer();

        assertTrue(export.contains("s.m.butscher@gmail.com"), "Die Login-Adresse gehoert hinein.");
        assertFalse(export.contains("$2a$10$sehrGeheimerHash"), "Kein Passwort-Hash im Export.");
        assertFalse(export.contains("JBSWY3DPEHPK3PXP"), "Kein TOTP-Secret im Export.");
        assertFalse(export.contains("abcdef0123"), "Keine Recovery-Codes im Export.");
        assertFalse(export.contains("subject-123"), "Kein OIDC-Subject im Export.");
        assertTrue(export.contains("plaintext-benutzer"), "Der Formatkopf muss dranstehen.");
    }

    @Test
    void exportNimmtNurBenutzerDesEigenenMandanten() {
        authAdmin();
        when(userRepository.findAll()).thenReturn(List.of(
                benutzer("drin@x.ch", "guild42"),
                benutzer("draussen@x.ch", "plaintext")));

        String export = tools.exportBenutzer();

        assertTrue(export.contains("drin@x.ch"));
        assertFalse(export.contains("draussen@x.ch"),
                "Ein Benutzer eines fremden Mandanten hat im Export nichts verloren.");
    }

    @Test
    void exportNimmtAuchBenutzerMitZusatzmandat() {
        authAdmin();
        UserMandate zusatz = new UserMandate();
        zusatz.setUsername("wechsler@x.ch");
        zusatz.setMandat("guild42");
        when(mandateRepository.findByMandatAndActiveTrue("guild42")).thenReturn(List.of(zusatz));
        when(userRepository.findAll()).thenReturn(List.of(benutzer("wechsler@x.ch", "plaintext")));

        assertTrue(tools.exportBenutzer().contains("wechsler@x.ch"),
                "Wer den Mandanten als Zusatzmandat hat, gehoert dazu — das sind gerade die "
                        + "Konten, die transportiert werden muessen.");
    }

    // ── Import ────────────────────────────────────────────────────────────────────────────────

    @Test
    void importVerwirftPrivilegierteRollen() {
        authAdmin();
        when(userRepository.findByUsername(anyString())).thenReturn(null);

        String bericht = tools.importBenutzer(datei("""
                {"username":"neu@x.ch","vorname":"Neu","rollen":["buchhaltung","admin","root",
                 "PROPERTY_MANDAT_PLAINTEXT"]}"""));

        MyUserEntity gespeichert = einzigerGespeicherter();
        assertTrue(gespeichert.getRoles().contains("buchhaltung"), "Modul-Rollen bleiben.");
        assertFalse(gespeichert.getRoles().contains("admin"), "admin darf nie aus einer Datei kommen.");
        assertFalse(gespeichert.getRoles().contains("root"), "root darf nie aus einer Datei kommen.");
        assertTrue(bericht.contains("verworfen"), "Das Verworfene gehoert in den Bericht.");
    }

    @Test
    void importSetztDenMandantenDesZiels() {
        authAdmin();
        when(userRepository.findByUsername(anyString())).thenReturn(null);

        tools.importBenutzer(datei("""
                {"username":"neu@x.ch","rollen":["PROPERTY_MANDAT_PLAINTEXT"]}"""));

        assertEquals("guild42", einzigerGespeicherter().getMandat(),
                "Der Mandant kommt vom Ziel, nie aus der Datei.");
    }

    @Test
    void neuesKontoIstNichtBenutzbar() {
        authAdmin();
        when(userRepository.findByUsername(anyString())).thenReturn(null);

        tools.importBenutzer(datei("""
                {"username":"neu@x.ch","passwordless":true}"""));

        MyUserEntity neu = einzigerGespeicherter();
        assertEquals("", neu.getPassword(), "Ein Import erzeugt kein Passwort.");
        assertFalse(neu.isPasswordless(),
                "passwordless aus der Datei wuerde fertig nutzbare Zugaenge erzeugen, von denen "
                        + "ihre Inhaber nichts wissen.");
        assertTrue(neu.isMustChangePassword());
    }

    @Test
    void bestehendesKontoWirdNichtUeberschrieben() {
        authAdmin();
        MyUserEntity vorhanden = benutzer("alt@x.ch", "guild42");
        vorhanden.setVorname("Daniel");
        vorhanden.setRoles(new java.util.HashSet<>(Set.of("admin", "PROPERTY_MANDAT_GUILD42")));
        when(userRepository.findByUsername("alt@x.ch")).thenReturn(vorhanden);

        String bericht = tools.importBenutzer(datei("""
                {"username":"alt@x.ch","vorname":"Fremd","nachname":"Neuer","rollen":["buchhaltung"]}"""));

        assertEquals("Daniel", vorhanden.getVorname(), "Ein gesetzter Name wird nicht ueberschrieben.");
        assertEquals("Neuer", vorhanden.getNachname(), "Was fehlt, darf ergaenzt werden.");
        assertTrue(vorhanden.getRoles().contains("admin"), "Rollen gehoeren dem Ziel, nicht der Datei.");
        assertFalse(vorhanden.getRoles().contains("buchhaltung"));
        assertTrue(bericht.contains("ergaenzt"));
    }

    @Test
    void ungueltigerLoginWirdUebersprungen() {
        authAdmin();

        String bericht = tools.importBenutzer(datei("""
                {"username":"kein-login"}"""));

        verify(userRepository, never()).findByUsername("kein-login");
        assertTrue(bericht.contains("uebersprungen"));
    }

    @Test
    void falschesFormatWirdAbgewiesen() {
        authAdmin();
        assertTrue(tools.importBenutzer("{\"format\":\"etwas-anderes\",\"version\":1}")
                .contains("Falsches Dateiformat"));
        verify(userRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void neuereVersionWirdAbgewiesen() {
        authAdmin();
        assertTrue(tools.importBenutzer("{\"format\":\"plaintext-benutzer\",\"version\":99}")
                .contains("neueren Version"));
        verify(userRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exportUndImportPassenZusammen() {
        authAdmin();
        when(userRepository.findAll()).thenReturn(List.of(benutzer("rund@x.ch", "guild42")));
        String export = tools.exportBenutzer();

        when(userRepository.findByUsername(anyString())).thenReturn(null);
        String bericht = tools.importBenutzer(export);

        assertTrue(bericht.startsWith("1 von 1"),
                "Was der Export schreibt, muss der Import lesen koennen: " + bericht);
    }

    // ── Hilfen ────────────────────────────────────────────────────────────────────────────────

    /** Packt einen Benutzersatz in einen gueltigen Dateikopf. */
    private static String datei(String satzJson) {
        return "{\"format\":\"plaintext-benutzer\",\"version\":1,\"quellMandat\":\"plaintext\","
                + "\"benutzer\":[" + satzJson + "]}";
    }

    private MyUserEntity einzigerGespeicherter() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MyUserEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userRepository).saveAll(captor.capture());
        List<MyUserEntity> gespeichert = captor.getValue();
        assertEquals(1, gespeichert.size(), "Erwartet wird genau ein geschriebener Benutzer.");
        return gespeichert.get(0);
    }

    private static MyUserEntity benutzer(String login, String mandat) {
        MyUserEntity u = new MyUserEntity();
        u.setUsername(login);
        u.setMandat(mandat);
        return u;
    }

    private void authentifiziereAls(String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a))
                .toList();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken("daniel@x.ch", null, granted));
        SecurityContextHolder.setContext(ctx);
    }

    private void authAdmin() {
        authentifiziereAls("ROLE_USER", "ROLE_ADMIN", "SCOPE_READ", "SCOPE_WRITE", "SCOPE_ADMIN",
                "PROPERTY_MYUSERID_2", "PROPERTY_MANDAT_guild42");
    }

    /** Gegenprobe zum Aufbau: ohne Mandat im Kontext bricht der Import ab, statt irgendwo zu landen. */
    @Test
    void ohneMandatKeinImport() {
        authAdmin();
        when(security.getMandat()).thenReturn(null);
        assertTrue(tools.importBenutzer(datei("{\"username\":\"neu@x.ch\"}")).contains("Kein Mandat"));
        assertNull(security.getMandat());
    }
}

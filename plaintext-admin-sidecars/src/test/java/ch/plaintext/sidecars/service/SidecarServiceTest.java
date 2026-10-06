/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import ch.plaintext.sidecars.SidecarVerbindung;
import ch.plaintext.sidecars.entity.AuthZustand;
import ch.plaintext.sidecars.entity.Sidecar;
import ch.plaintext.sidecars.entity.SidecarQuelle;
import ch.plaintext.sidecars.repository.SidecarRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Karte 1400: Registry, Konfiguration, erlaubte Hosts, Token. */
class SidecarServiceTest {

    final List<Sidecar> db = new ArrayList<>();
    final AtomicLong ids = new AtomicLong();

    SidecarService service(String konfig, String erlaubt) {
        SidecarRepository repo = mock(SidecarRepository.class);
        when(repo.save(any())).thenAnswer(i -> {
            Sidecar s = i.getArgument(0);
            if (s.getId() == null) {
                s.setId(ids.incrementAndGet());
                db.add(s);
            }
            return s;
        });
        when(repo.findByDeletedFalseOrderByNameAsc()).thenAnswer(i -> db.stream().filter(s -> !Boolean.TRUE.equals(s.getDeleted()))
                .sorted(Comparator.comparing(Sidecar::getName)).toList());
        when(repo.findFirstByNameAndDeletedFalse(anyString())).thenAnswer(i -> db.stream()
                .filter(s -> !Boolean.TRUE.equals(s.getDeleted()) && s.getName().equals(i.getArgument(0))).findFirst());
        return new SidecarService(repo, new SidecarProtokollClient(), new SidecarCrypto(), konfig, erlaubt);
    }

    @Test
    @DisplayName("Konfiguration: name=url, ungültige Einträge übergangen, URL ohne Schrägstrich am Ende")
    void konfig() {
        assertThat(SidecarService.leseKonfig("whatsapp=http://messenger-sidecar:3000/, kaputt, Gross=http://x, f=ftp://x, u=http://a:b@x"))
                .containsExactly(java.util.Map.entry("whatsapp", "http://messenger-sidecar:3000"));
    }

    @Test
    @DisplayName("Abgleich beim Start legt Konfig-Einträge an und entfernt verschwundene; Abfrage füllt den Stand")
    void abgleichUndAbfrage() throws Exception {
        try (TestSidecar t = new TestSidecar("test", "eingeschraenkt")) {
            SidecarService s = service("test=" + t.url(), "");
            s.abgleichen();
            assertThat(s.liste()).extracting(Sidecar::getName).containsExactly("test");
            s.aktualisiereAlle();
            Sidecar x = s.sidecar("test");
            assertThat(x.isErreichbar()).isTrue();
            assertThat(x.getStatus()).isEqualTo("eingeschraenkt");
            assertThat(x.getAuthZustand()).isEqualTo(AuthZustand.KEIN_TOKEN);
            assertThat(x.getQuelle()).isEqualTo(SidecarQuelle.KONFIG);

            service("", "").abgleichen();
            assertThat(db.getFirst().getDeleted()).as("nicht mehr konfiguriert").isTrue();
        }
    }

    @Test
    @DisplayName("Token: verschlüsselt gespeichert, sofort geprüft, in der Verbindung lesbar, im toString nicht")
    void token() throws Exception {
        try (TestSidecar t = new TestSidecar("test", "ok")) {
            SidecarService s = service("test=" + t.url(), "");
            s.abgleichen();
            assertThat(s.setzeToken("test", TestSidecar.TOKEN)).isEqualTo(AuthZustand.GUELTIG);
            Sidecar x = s.sidecar("test");
            assertThat(x.getTokenEncrypted()).isNotBlank().doesNotContain(TestSidecar.TOKEN);
            assertThat(x.toString()).doesNotContain(x.getTokenEncrypted());
            SidecarVerbindung v = s.verbindung("test").orElseThrow();
            assertThat(v.token()).isEqualTo(TestSidecar.TOKEN);
            assertThat(v.toString()).doesNotContain(TestSidecar.TOKEN);
            assertThat(s.setzeToken("test", "falsch")).isEqualTo(AuthZustand.UNGUELTIG);
            assertThat(s.setzeToken("test", " ")).isEqualTo(AuthZustand.KEIN_TOKEN);
            assertThat(s.sidecar("test").hatToken()).isFalse();
        }
    }

    @Test
    @DisplayName("Von Hand ergänzen: nur erlaubte Hosts, Name aus der Beschreibung, keine Doppelten")
    void registrieren() throws Exception {
        try (TestSidecar t = new TestSidecar("zwei", "ok")) {
            SidecarService gesperrt = service("", "");
            assertThatThrownBy(() -> gesperrt.registriere(t.url())).hasMessageContaining("nicht freigegeben");
            assertThat(t.aufrufe).as("ein nicht freigegebener Host wird gar nicht erst aufgerufen").isEmpty();

            SidecarService s = service("", "127.0.0.1");
            Sidecar x = s.registriere(t.url() + "/");
            assertThat(x.getName()).isEqualTo("zwei");
            assertThat(x.getQuelle()).isEqualTo(SidecarQuelle.HAND);
            assertThatThrownBy(() -> s.registriere(t.url())).hasMessageContaining("gibt es schon");
            assertThatThrownBy(() -> s.registriere("file:///etc/passwd")).hasMessageContaining("http");
            s.entferne("zwei");
            assertThat(s.liste()).isEmpty();
        }
    }

    @Test
    @DisplayName("Konfig-Einträge lassen sich nicht von Hand entfernen; Wildcard-Hosts")
    void grenzen() throws Exception {
        try (TestSidecar t = new TestSidecar("test", "ok")) {
            SidecarService s = service("test=" + t.url(), "*.intern.example");
            s.abgleichen();
            assertThatThrownBy(() -> s.entferne("test")).hasMessageContaining("plaintext.sidecars");
            assertThat(s.hostErlaubt("http://fotos.intern.example:3003")).isTrue();
            assertThat(s.hostErlaubt("http://intern.example.boese.example")).isFalse();
            assertThat(s.hostErlaubt("http://127.0.0.1:1")).as("Host aus der Konfiguration").isTrue();
        }
    }

    @Test
    @DisplayName("Registry nach Fähigkeit: nur erreichbare, ok vor eingeschränkt, fehler nie")
    void fuerFaehigkeit() throws Exception {
        try (TestSidecar a = new TestSidecar("a", "eingeschraenkt"); TestSidecar b = new TestSidecar("b", "ok");
             TestSidecar c = new TestSidecar("c", "fehler")) {
            SidecarService s = service("a=" + a.url() + ",b=" + b.url() + ",c=" + c.url() + ",d=http://127.0.0.1:1", "");
            s.abgleichen();
            s.aktualisiereAlle();
            assertThat(s.anbieter("bild.info")).extracting(Sidecar::getName).containsExactly("b", "a");
            assertThat(s.fuer("bild.info")).map(SidecarVerbindung::name).contains("b");
            assertThat(s.fuer("gibt.es.nicht")).isEmpty();
            assertThat(s.alle()).extracting(st -> st.name() + ":" + st.status())
                    .containsExactly("a:eingeschraenkt", "b:ok", "c:fehler", "d:unbekannt");
        }
    }
}

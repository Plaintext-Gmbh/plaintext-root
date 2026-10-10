/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import ch.plaintext.ablagen.DateiAblage;
import ch.plaintext.sidecars.ablage.GitAblage;
import ch.plaintext.sidecars.ablage.NextcloudAblage;
import ch.plaintext.sidecars.ablage.TestWebDav;
import ch.plaintext.sidecars.entity.SpeicherAblage;
import ch.plaintext.sidecars.repository.SpeicherAblageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Karte 1406: Speicher-Ablagen einrichten, prüfen, über das Register nutzen. */
class SpeicherAblageServiceTest {

    final List<SpeicherAblage> db = new ArrayList<>();
    final AtomicLong ids = new AtomicLong();
    @TempDir
    Path klone;

    SpeicherAblageService service(String erlaubt) {
        SpeicherAblageRepository repo = mock(SpeicherAblageRepository.class);
        when(repo.save(any())).thenAnswer(i -> {
            SpeicherAblage s = i.getArgument(0);
            if (s.getId() == null) {
                s.setId(ids.incrementAndGet());
                db.add(s);
            }
            return s;
        });
        when(repo.findByDeletedFalseOrderByNameAsc()).thenAnswer(i -> db.stream().filter(s -> !Boolean.TRUE.equals(s.getDeleted())).toList());
        when(repo.findFirstByNameAndDeletedFalse(anyString())).thenAnswer(i -> db.stream()
                .filter(s -> !Boolean.TRUE.equals(s.getDeleted()) && s.getName().equals(i.getArgument(0))).findFirst());
        return new SpeicherAblageService(repo, new SidecarCrypto(), erlaubt, NextcloudAblage.standardClient(), klone);
    }

    @Test
    @DisplayName("Einrichten: Passwort verschlüsselt, sofort geprüft, über das Register nutzbar")
    void einrichtenUndNutzen() throws Exception {
        try (TestWebDav s = new TestWebDav()) {
            SpeicherAblageService svc = service("127.0.0.1");
            SpeicherAblage a = svc.speichere("nextcloud-drawio", s.url(), TestWebDav.BENUTZER, TestWebDav.PASSWORT, "Projekte/drawio");
            assertThat(a.getOk()).isTrue();
            assertThat(a.getPasswortEncrypted()).isNotBlank().doesNotContain(TestWebDav.PASSWORT);
            assertThat(a.toString()).doesNotContain(a.getPasswortEncrypted());

            DateiAblage d = svc.ablage("nextcloud-drawio").orElseThrow();
            d.schreibe("test.drawio", "<mxfile/>".getBytes(StandardCharsets.UTF_8), "application/xml");
            assertThat(s.dateien).containsKey("/remote.php/dav/files/anna/Projekte/drawio/test.drawio");
            assertThat(svc.namen()).containsExactly("nextcloud-drawio");

            SpeicherAblage ohnePw = svc.speichere("nextcloud-drawio", s.url(), TestWebDav.BENUTZER, " ", "Projekte/drawio");
            assertThat(ohnePw.getOk()).as("leeres Passwort beim Ändern behält das bisherige").isTrue();
        }
    }

    @Test
    @DisplayName("Falsches Passwort wird gespeichert, aber als nicht erreichbar gemeldet")
    void falschesPasswort() throws Exception {
        try (TestWebDav s = new TestWebDav()) {
            SpeicherAblage a = service("127.0.0.1").speichere("x", s.url(), TestWebDav.BENUTZER, "falsch", "Projekte/drawio");
            assertThat(a.getOk()).isFalse();
            assertThat(a.getMeldung()).contains("Anmeldung abgelehnt");
        }
    }

    @Test
    @DisplayName("Gegenprobe SSRF: interne Adresse ohne Freigabe wird abgewiesen, ohne Aufruf")
    void interneAdresse() throws Exception {
        try (TestWebDav s = new TestWebDav()) {
            SpeicherAblageService svc = service("");
            assertThatThrownBy(() -> svc.speichere("x", s.url(), TestWebDav.BENUTZER, TestWebDav.PASSWORT, "Projekte"))
                    .hasMessageContaining("interne Adresse");
            assertThat(s.aufrufe).isEmpty();
            assertThat(db).isEmpty();
        }
    }

    @Test
    @DisplayName("Pflichtangaben: Name, Benutzer, Pfad, Passwort beim Anlegen; keine Zugangsdaten in der URL")
    void pflicht() {
        SpeicherAblageService svc = service("127.0.0.1");
        assertThatThrownBy(() -> svc.speichere("Gross", "http://127.0.0.1:1", "a", "p", "x")).hasMessageContaining("Name");
        assertThatThrownBy(() -> svc.speichere("a", "http://127.0.0.1:1", " ", "p", "x")).hasMessageContaining("Benutzer");
        assertThatThrownBy(() -> svc.speichere("a", "http://127.0.0.1:1", "a", "p", " ")).hasMessageContaining("Pfad");
        assertThatThrownBy(() -> svc.speichere("a", "http://127.0.0.1:1", "a", null, "x")).hasMessageContaining("App-Passwort");
        assertThatThrownBy(() -> svc.speichere("a", "http://u:p@127.0.0.1:1", "a", "p", "x")).hasMessageContaining("Zugangsdaten");
        assertThatThrownBy(() -> svc.speichere("a", "http://127.0.0.1:1", "a", "p", "../x")).hasMessageContaining("Pfad");
    }

    @Test
    @DisplayName("Karte 1471: GIT wird mit Zweig und verschlüsseltem Token gespeichert, geprüft und als GitAblage geöffnet")
    void gitEinrichten() {
        SpeicherAblageService svc = service("127.0.0.1");
        // Port 1 auf localhost: niemand hört zu, die Prüfung scheitert lokal, ohne Netz.
        SpeicherAblage a = svc.speichere("git-praesentation", "git", "http://127.0.0.1:1/repo.git", "x-access-token", "tok-123", "folien", "main");
        assertThat(a.getArt()).isEqualTo(SpeicherAblage.ART_GIT);
        assertThat(a.getZweig()).isEqualTo("main");
        assertThat(a.getPasswortEncrypted()).isNotBlank().doesNotContain("tok-123");
        assertThat(a.getOk()).isFalse();
        assertThat(a.getMeldung()).startsWith("Git:").doesNotContain("tok-123");
        assertThat(svc.ablage("git-praesentation")).get().isInstanceOf(GitAblage.class);
        assertThat(klone).isDirectoryContaining(p -> p.getFileName().toString().startsWith("git-praesentation-"));

        SpeicherAblage wurzel = svc.speichere("git-wurzel", "GIT", "http://127.0.0.1:1/repo.git", "u", "t", " ", "main");
        assertThat(wurzel.getPfad()).as("bei Git ist der Unterordner optional").isEmpty();
    }

    @Test
    @DisplayName("Karte 1471: GIT-Pflichtangaben und dieselbe Adressprüfung wie Nextcloud")
    void gitPflicht() {
        SpeicherAblageService svc = service("127.0.0.1");
        assertThatThrownBy(() -> svc.speichere("g", "GIT", "http://127.0.0.1:1/r.git", "u", "t", "", " ")).hasMessageContaining("Zweig");
        assertThatThrownBy(() -> svc.speichere("g", "GIT", "http://127.0.0.1:1/r.git", "u", "t", "", "a..b")).hasMessageContaining("Zweig");
        assertThatThrownBy(() -> svc.speichere("g", "GIT", "http://127.0.0.1:1/r.git", "u", null, "", "main")).hasMessageContaining("Token");
        assertThatThrownBy(() -> svc.speichere("g", "GIT", "http://127.0.0.1:1/r.git", "u", "t", "../x", "main")).hasMessageContaining("Pfad");
        assertThatThrownBy(() -> svc.speichere("g", "SVN", "http://127.0.0.1:1/r.git", "u", "t", "", "main")).hasMessageContaining("Art");
        assertThatThrownBy(() -> svc.speichere("g", "GIT", "file:///tmp/r.git", "u", "t", "", "main")).hasMessageContaining("Adresse");
        assertThatThrownBy(() -> service("").speichere("g", "GIT", "http://127.0.0.1:1/r.git", "u", "t", "", "main"))
                .hasMessageContaining("interne Adresse");
        assertThat(db).isEmpty();
    }
}

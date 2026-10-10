/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.ablage;

import ch.plaintext.ablagen.AblageEintrag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Karte 1406: Nextcloud-Ablage gegen einen WebDAV-Testserver. */
class NextcloudAblageTest {

    static NextcloudAblage ablage(TestWebDav s, String passwort) throws IOException {
        return new NextcloudAblage("test", s.url(), TestWebDav.BENUTZER, passwort, "Projekte/drawio", NextcloudAblage.standardClient());
    }

    @Test
    @DisplayName("Positivkontrolle: prüfen, schreiben (Ordner werden angelegt), lesen, auflisten, löschen")
    void rundlauf() throws Exception {
        try (TestWebDav s = new TestWebDav()) {
            NextcloudAblage a = ablage(s, TestWebDav.PASSWORT);
            assertThat(a.wurzel().getPath()).isEqualTo("/remote.php/dav/files/anna/Projekte/drawio/");
            assertThat(a.pruefe()).startsWith("Verbindung in Ordnung");

            a.schreibe("app/mind map.drawio", "<mxfile/>".getBytes(StandardCharsets.UTF_8), "application/xml");
            assertThat(s.dateien).containsKey("/remote.php/dav/files/anna/Projekte/drawio/app/mind map.drawio");
            assertThat(s.aufrufe).contains("MKCOL /remote.php/dav/files/anna/Projekte/drawio/app/",
                    "PUT /remote.php/dav/files/anna/Projekte/drawio/app/mind%20map.drawio");
            assertThat(new String(a.lies("app/mind map.drawio"), StandardCharsets.UTF_8)).isEqualTo("<mxfile/>");
            assertThat(a.existiert("app/mind map.drawio")).isTrue();
            assertThat(a.existiert("app/fehlt.drawio")).isFalse();
            assertThat(a.liste("")).extracting(AblageEintrag::pfad, AblageEintrag::ordner).containsExactly(org.assertj.core.groups.Tuple.tuple("app", true));
            assertThat(a.liste("app")).extracting(AblageEintrag::pfad).containsExactly("app/mind map.drawio");

            a.schreibe("app/mind map.drawio", "<mxfile v=\"2\"/>".getBytes(StandardCharsets.UTF_8), null);
            assertThat(new String(a.lies("app/mind map.drawio"), StandardCharsets.UTF_8)).as("überschreiben").contains("v=\"2\"");
            a.loesche("app/mind map.drawio");
            assertThat(a.existiert("app/mind map.drawio")).isFalse();
        }
    }

    @Test
    @DisplayName("Falsches App-Passwort: verständliche Meldung, nichts geschrieben")
    void falschesPasswort() throws Exception {
        try (TestWebDav s = new TestWebDav()) {
            NextcloudAblage a = ablage(s, "falsch");
            assertThatThrownBy(a::pruefe).hasMessageContaining("Anmeldung abgelehnt");
            assertThatThrownBy(() -> a.schreibe("x.txt", new byte[]{1}, null)).hasMessageContaining("Anmeldung abgelehnt");
            assertThat(s.dateien.keySet()).noneMatch(k -> k.endsWith("x.txt"));
        }
    }

    @Test
    @DisplayName("Kein Weg aus dem Ordner: .., absolute Pfade, leere Teile, Backslash")
    void pfadgrenzen() throws Exception {
        try (TestWebDav s = new TestWebDav()) {
            NextcloudAblage a = ablage(s, TestWebDav.PASSWORT);
            for (String boese : new String[]{"../geheim.txt", "a/../../x", "/etc/passwd", "a//b", ".", "a\\b", "", "a/./b"}) {
                assertThatThrownBy(() -> a.schreibe(boese, new byte[]{1}, null)).as(boese).hasMessageContaining("Ungültiger Pfad");
            }
            assertThatThrownBy(() -> a.liste("../")).hasMessageContaining("Ungültiger Pfad");
            assertThat(s.aufrufe).as("kein einziger Aufruf für einen ungültigen Pfad").isEmpty();
        }
    }

    @Test
    @DisplayName("Karte 1475: Ordner anlegen, verschieben (MOVE ohne Überschreiben), leer/rekursiv löschen")
    void ordner() throws Exception {
        String w = "/remote.php/dav/files/anna/Projekte/drawio/";
        try (TestWebDav s = new TestWebDav()) {
            NextcloudAblage a = ablage(s, TestWebDav.PASSWORT);
            a.legeOrdnerAn("neu/tief");
            assertThat(s.dateien).containsKeys(w + "neu/", w + "neu/tief/");
            a.legeOrdnerAn("neu"); // gibt es schon: kein Fehler

            a.schreibe("neu/tief/a b.drawio", new byte[]{1}, null);
            a.verschiebe("neu/tief/a b.drawio", "neu/umbenannt.drawio");
            assertThat(s.aufrufe).contains("MOVE /remote.php/dav/files/anna/Projekte/drawio/neu/tief/a%20b.drawio");
            assertThat(s.dateien).containsKey(w + "neu/umbenannt.drawio").doesNotContainKey(w + "neu/tief/a b.drawio");

            a.verschiebe("neu", "archiv/2026/neu"); // Elternordner des Ziels werden angelegt
            assertThat(s.dateien).containsKeys(w + "archiv/2026/neu/tief/", w + "archiv/2026/neu/umbenannt.drawio")
                    .doesNotContainKey(w + "neu/");

            a.legeOrdnerAn("zweiter");
            assertThatThrownBy(() -> a.verschiebe("zweiter", "archiv")).hasMessageContaining("gibt es schon");
            int vorher = s.aufrufe.size();
            assertThatThrownBy(() -> a.verschiebe("archiv", "archiv/2026/drin")).hasMessageContaining("in sich selbst");
            assertThatThrownBy(() -> a.verschiebe("archiv", "archiv")).hasMessageContaining("in sich selbst");
            assertThat(s.aufrufe).as("kein Aufruf beim Verschieben in sich selbst").hasSize(vorher);

            assertThatThrownBy(() -> a.loescheOrdner("archiv", false)).isInstanceOf(java.nio.file.DirectoryNotEmptyException.class);
            assertThat(s.dateien).containsKey(w + "archiv/2026/neu/umbenannt.drawio");
            assertThatThrownBy(() -> a.loescheOrdner("archiv/2026/neu/umbenannt.drawio", true)).isInstanceOf(IOException.class);
            assertThat(s.dateien).as("eine Datei ist kein Ordner").containsKey(w + "archiv/2026/neu/umbenannt.drawio");
            a.loescheOrdner("zweiter", false);
            a.loescheOrdner("archiv", true);
            assertThat(s.dateien.keySet()).noneMatch(k -> k.startsWith(w + "archiv") || k.startsWith(w + "zweiter"));
        }
    }

    @Test
    @DisplayName("Karte 1475: Ordner-Operationen halten die Pfadgrenzen ein, ohne einen Aufruf")
    void ordnerPfadgrenzen() throws Exception {
        try (TestWebDav s = new TestWebDav()) {
            NextcloudAblage a = ablage(s, TestWebDav.PASSWORT);
            for (String boese : new String[]{"../geheim", "a/../../x", "/etc", "a//b", ".", "a\\b", "", "a/./b"}) {
                assertThatThrownBy(() -> a.legeOrdnerAn(boese)).as(boese).hasMessageContaining("Ungültiger Pfad");
                assertThatThrownBy(() -> a.loescheOrdner(boese, true)).as(boese).hasMessageContaining("Ungültiger Pfad");
                assertThatThrownBy(() -> a.verschiebe("x", boese)).as(boese).hasMessageContaining("Ungültiger Pfad");
                assertThatThrownBy(() -> a.verschiebe(boese, "x")).as(boese).hasMessageContaining("Ungültiger Pfad");
            }
            assertThat(s.aufrufe).isEmpty();
        }
    }
}

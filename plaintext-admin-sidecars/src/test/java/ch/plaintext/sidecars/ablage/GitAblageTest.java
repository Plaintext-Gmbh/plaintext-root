/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.ablage;

import ch.plaintext.ablagen.AblageEintrag;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Karte 1471: Git-Ablage gegen ein lokales bare-Repo im Temp-Verzeichnis, ohne Netz. */
class GitAblageTest {

    @TempDir
    Path tmp;
    Path bare;
    String url;

    @BeforeEach
    void bareRepo() throws Exception {
        bare = tmp.resolve("repo.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        url = bare.toUri().toString();
    }

    GitAblage ablage(String klon, String ordner, String autor) throws IOException {
        return new GitAblage("test", url, "main", ordner, "x-access-token", "geheim", tmp.resolve(klon), () -> autor);
    }

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    List<RevCommit> commits() throws Exception {
        try (Git g = Git.open(bare.toFile())) {
            List<RevCommit> l = new ArrayList<>();
            if (g.getRepository().resolve("HEAD") == null) {
                return l;
            }
            g.log().call().forEach(l::add);
            return l;
        }
    }

    @Test
    @DisplayName("Anlegen, lesen, ändern, auflisten, löschen: je ein Commit mit Autor und Dateiname, Push ins bare-Repo")
    void kreislauf() throws Exception {
        GitAblage a = ablage("klon-a", "", "anna@example.ch");
        assertThat(a.pruefe()).contains("noch leer");

        a.schreibe("diagramme/a.drawio", b("<mxfile/>"), "application/xml");
        assertThat(a.lies("diagramme/a.drawio")).isEqualTo(b("<mxfile/>"));
        a.schreibe("diagramme/a.drawio", b("<mxfile v=\"2\"/>"), null);
        a.schreibe("diagramme/a.drawio", b("<mxfile v=\"2\"/>"), null); // unverändert: kein leerer Commit

        // Ein zweiter, frischer Klon sieht den gepushten Stand: es liegt im bare-Repo, nicht nur lokal.
        GitAblage frisch = ablage("klon-b", "", "bert@example.ch");
        assertThat(frisch.lies("diagramme/a.drawio")).isEqualTo(b("<mxfile v=\"2\"/>"));
        assertThat(frisch.existiert("diagramme")).isTrue();
        assertThat(frisch.liste("")).extracting(AblageEintrag::pfad, AblageEintrag::ordner).containsExactly(
                org.assertj.core.groups.Tuple.tuple("diagramme", true));
        assertThat(frisch.liste("diagramme")).singleElement().satisfies(e -> {
            assertThat(e.pfad()).isEqualTo("diagramme/a.drawio");
            assertThat(e.groesse()).isEqualTo(15);
        });
        assertThat(frisch.liste("gibt-es-nicht")).isEmpty();
        assertThat(frisch.pruefe()).contains("1 Einträge");

        frisch.loesche("diagramme/a.drawio");
        assertThat(a.existiert("diagramme/a.drawio")).isFalse();
        assertThatThrownBy(() -> a.lies("diagramme/a.drawio")).hasMessageContaining("nicht gefunden");

        List<RevCommit> c = commits();
        assertThat(c).hasSize(3);
        assertThat(c.get(0).getAuthorIdent().getName()).isEqualTo("bert@example.ch");
        assertThat(c.get(0).getFullMessage()).contains("diagramme/a.drawio").contains("gelöscht");
        assertThat(c.get(2).getAuthorIdent().getEmailAddress()).isEqualTo("anna@example.ch");
        assertThat(c.get(2).getFullMessage()).isEqualTo("Ablage test: diagramme/a.drawio gespeichert");
    }

    @Test
    @DisplayName("Konflikt: wird zwischen Commit und Push fremd gepusht, wird abgelehnt gemeldet und nichts überschrieben")
    void konflikt() throws Exception {
        GitAblage a = ablage("klon-a", "", "anna@example.ch");
        GitAblage b = ablage("klon-b", "", "bert@example.ch");
        a.schreibe("start.txt", b("1"), null);

        a.vorPush = () -> {
            try {
                b.schreibe("start.txt", b("von bert"), null);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        assertThatThrownBy(() -> a.schreibe("start.txt", b("von anna"), null))
                .isInstanceOf(IOException.class).hasMessageContaining("Konflikt").hasMessageContaining("Nichts überschrieben");
        assertThat(commits().get(0).getAuthorIdent().getName()).isEqualTo("bert@example.ch");
        assertThat(a.lies("start.txt")).as("der lokale Commit ist verworfen, es gilt der Stand im Repo").isEqualTo(b("von bert"));

        a.vorPush = () -> { };
        a.schreibe("start.txt", b("von anna"), null);
        assertThat(b.lies("start.txt")).isEqualTo(b("von anna"));
        assertThat(commits()).hasSize(3);
    }

    @Test
    @DisplayName("Unterordner: Pfade sind relativ zu ihm, ausserhalb liegt nichts")
    void unterordner() throws Exception {
        GitAblage a = ablage("klon-a", "praesentationen/2026", "anna@example.ch");
        a.schreibe("folien.md", b("# Hallo"), "text/markdown");
        assertThat(a.liste("")).extracting(AblageEintrag::pfad).containsExactly("folien.md");
        assertThat(ablage("klon-b", "", "x").lies("praesentationen/2026/folien.md")).isEqualTo(b("# Hallo"));
    }

    @Test
    @DisplayName("Pfad-Traversal und .git werden abgewiesen, ohne Commit")
    void pfade() throws Exception {
        GitAblage a = ablage("klon-a", "ordner", "anna@example.ch");
        for (String p : List.of("../x.txt", "/etc/passwd", "a/../../x", ".git/config", "a/.GIT/hooks/pre-commit", "a\\b", "", "a//b", "./x")) {
            assertThatThrownBy(() -> a.schreibe(p, b("x"), null)).as(p).hasMessageContaining("Ungültiger Pfad");
            assertThatThrownBy(() -> a.lies(p)).as(p).hasMessageContaining("Ungültiger Pfad");
        }
        assertThatThrownBy(() -> a.liste("../")).hasMessageContaining("Ungültiger Pfad");
        assertThatThrownBy(() -> ablage("klon-b", "../aussen", "x")).hasMessageContaining("Ungültiger Pfad");
        assertThatThrownBy(() -> new GitAblage("t", url, "main..x", "", "u", "t", tmp.resolve("k"), () -> "x"))
                .hasMessageContaining("Zweig");
        assertThat(commits()).isEmpty();
    }

    @Test
    @DisplayName("Ein Symlink im Repo führt nicht aus dem Klon heraus (core.symlinks=false)")
    void symlink() throws Exception {
        Path geheim = Files.writeString(tmp.resolve("geheim.txt"), "GEHEIM");
        Path fremd = tmp.resolve("fremd");
        GitAblage a = ablage("klon-a", "", "x");
        a.schreibe("start.txt", b("1"), null); // legt main an, sonst klont das leere Repo auf master
        try (Git g = Git.cloneRepository().setURI(url).setBranch("main").setDirectory(fremd.toFile()).call()) {
            Files.createSymbolicLink(fremd.resolve("link"), geheim);
            g.add().addFilepattern("link").call();
            g.commit().setMessage("link").setSign(false).call();
            g.push().call();
        }
        byte[] gelesen = a.lies("link");
        assertThat(new String(gelesen, StandardCharsets.UTF_8)).doesNotContain("GEHEIM").isEqualTo(geheim.toString());
    }

    @Test
    @DisplayName("Rechte: ohne Schreibrecht auf das Repo scheitert der Push mit Meldung; mit Schreibrecht geht er durch")
    void rechte() throws Exception {
        assumeTrue(!"root".equals(System.getProperty("user.name")), "als root greifen Dateirechte nicht");
        GitAblage a = ablage("klon-a", "", "anna@example.ch");
        a.schreibe("ok.txt", b("1"), null); // Positivprobe

        try (Stream<Path> s = Files.walk(bare)) {
            for (Path p : s.toList()) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "r-xr-xr-x" : "r--r--r--"));
            }
        }
        try {
            assertThatThrownBy(() -> a.schreibe("nein.txt", b("2"), null)).isInstanceOf(IOException.class);
            assertThat(a.existiert("nein.txt")).as("abgelehnter Stand bleibt nicht im Klon").isFalse();
            assertThat(commits()).hasSize(1);
        } finally {
            try (Stream<Path> s = Files.walk(bare)) {
                for (Path p : s.toList()) {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
                }
            }
        }
    }

    @Test
    @DisplayName("Fehlermeldungen: Anmeldung, Zugriff, Weiterleitung klar; sonst Git-Text")
    void meldungen() {
        assertThat(GitAblage.meldung(new IOException("https://x: not authorized"))).contains("Anmeldung abgelehnt");
        assertThat(GitAblage.meldung(new IOException("403 Forbidden"))).contains("Zugriff verweigert");
        assertThat(GitAblage.meldung(new IOException("Too many redirects"))).contains("leitet weiter");
        assertThat(GitAblage.meldung(new IOException("kaputt"))).isEqualTo("Git: kaputt");
        assertThat(Repository.isValidRefName("refs/heads/main")).isTrue();
    }

    @Test
    @DisplayName("Karte 1475: Ordner anlegen (.gitkeep), verschieben, umbenennen, löschen: je ein Commit, für einen frischen Klon sichtbar")
    void ordner() throws Exception {
        GitAblage a = ablage("klon-a", "", "anna@example.ch");
        a.legeOrdnerAn("leer");
        a.legeOrdnerAn("leer"); // gibt es schon: kein Commit
        GitAblage frisch = ablage("klon-b", "", "bert@example.ch");
        assertThat(frisch.liste("")).extracting(AblageEintrag::pfad, AblageEintrag::ordner)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("leer", true));
        assertThat(frisch.liste("leer")).as(".gitkeep ist ausgeblendet").isEmpty();
        assertThat(commits()).hasSize(1);

        a.schreibe("alt/x.txt", b("x"), null);
        a.verschiebe("alt", "leer/neu");
        a.verschiebe("leer/neu/x.txt", "leer/neu/y.txt");
        assertThat(frisch.lies("leer/neu/y.txt")).isEqualTo(b("x"));
        assertThat(frisch.existiert("alt")).isFalse();
        assertThat(commits().get(1).getFullMessage()).isEqualTo("Ablage test: alt nach leer/neu verschoben");

        assertThatThrownBy(() -> a.verschiebe("leer/neu/y.txt", "leer/neu/y.txt")).hasMessageContaining("in sich selbst");
        assertThatThrownBy(() -> a.verschiebe("leer", "leer/neu/tiefer")).hasMessageContaining("in sich selbst");
        a.schreibe("z.txt", b("z"), null);
        assertThatThrownBy(() -> a.verschiebe("z.txt", "leer/neu/y.txt")).hasMessageContaining("gibt es schon");
        assertThatThrownBy(() -> a.verschiebe("fehlt.txt", "w.txt")).hasMessageContaining("nicht gefunden");
        assertThatThrownBy(() -> a.legeOrdnerAn("z.txt")).hasMessageContaining("Datei");
        assertThat(frisch.lies("z.txt")).isEqualTo(b("z"));

        assertThatThrownBy(() -> frisch.loescheOrdner("leer", false)).isInstanceOf(java.nio.file.DirectoryNotEmptyException.class);
        assertThatThrownBy(() -> frisch.loescheOrdner("z.txt", true)).hasMessageContaining("nicht gefunden");
        frisch.legeOrdnerAn("nur-platzhalter");
        frisch.loescheOrdner("nur-platzhalter", false); // nur .gitkeep = leer
        frisch.loescheOrdner("leer", true);
        assertThat(a.liste("")).extracting(AblageEintrag::pfad).containsExactly("z.txt");
        assertThat(commits()).hasSize(8);
        assertThat(commits().get(0).getAuthorIdent().getName()).isEqualTo("bert@example.ch");
        assertThat(commits().get(0).getFullMessage()).isEqualTo("Ablage test: Ordner leer gelöscht");
    }

    @Test
    @DisplayName("Karte 1475: Ordner-Operationen lehnen Traversal und .git ab, ohne Commit; Konflikt wird gemeldet")
    void ordnerPfadeUndKonflikt() throws Exception {
        GitAblage a = ablage("klon-a", "ordner", "anna@example.ch");
        for (String p : List.of("../x", "/etc", "a/../../x", ".git", "a/.GIT/hooks", "a\\b", "", "a//b", "./x")) {
            assertThatThrownBy(() -> a.legeOrdnerAn(p)).as(p).hasMessageContaining("Ungültiger Pfad");
            assertThatThrownBy(() -> a.loescheOrdner(p, true)).as(p).hasMessageContaining("Ungültiger Pfad");
            assertThatThrownBy(() -> a.verschiebe("x", p)).as(p).hasMessageContaining("Ungültiger Pfad");
            assertThatThrownBy(() -> a.verschiebe(p, "x")).as(p).hasMessageContaining("Ungültiger Pfad");
        }
        assertThat(commits()).isEmpty();

        GitAblage b = ablage("klon-b", "ordner", "bert@example.ch");
        a.schreibe("start.txt", b("1"), null);
        a.vorPush = () -> {
            try {
                b.legeOrdnerAn("von-bert");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        assertThatThrownBy(() -> a.verschiebe("start.txt", "weg.txt")).hasMessageContaining("Konflikt");
        assertThat(b.existiert("start.txt")).isTrue();
        assertThat(b.existiert("weg.txt")).isFalse();
    }
}

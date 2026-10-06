/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.katalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Karte 1405: der Prozessor schreibt den Katalog und verlangt einen Zweck. */
class SchnittstellenKatalogProzessorTest {

    @TempDir
    Path tmp;

    private DiagnosticCollector<JavaFileObject> kompiliere(String quelle, boolean streng) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/ch/x"));
        Files.writeString(src.resolve("Dienst.java"), quelle);
        Path out = Files.createDirectories(tmp.resolve("out"));
        JavaCompiler c = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> d = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = c.getStandardFileManager(d, null, null)) {
            var units = fm.getJavaFileObjects(src.resolve("Dienst.java").toFile());
            var task = c.getTask(null, fm, d, List.of("-d", out.toString(), "-proc:only",
                    "-Aplaintext.katalog.modul=test-interfaces", "-Aplaintext.katalog.streng=" + streng), null, units);
            task.setProcessors(List.of(new SchnittstellenKatalogProzessor()));
            task.call();
        }
        return d;
    }

    @Test
    @DisplayName("Positivkontrolle: Schnittstelle, Zweck, Methoden mit Parametern und Javadoc stehen im Katalog")
    void katalog() throws Exception {
        var d = kompiliere("""
                package ch.x;
                /** Rechnet Preise um. Zweite Zeile. */
                public interface Dienst {
                    /** Wandelt um. @param betrag in Rappen */
                    long umrechnen(long betrag, String waehrung);
                    default String name() { return "x"; }
                    /** Ein verschachtelter Vertrag. */
                    interface Teil { void tu(); }
                }
                """, true);
        assertThat(d.getDiagnostics()).noneMatch(x -> x.getKind() == Diagnostic.Kind.ERROR);
        JsonNode k = JsonMapper.builderWithJackson2Defaults().build().readTree(tmp.resolve("out/META-INF/plaintext-katalog/test-interfaces.json").toFile());
        assertThat(k.path("modul").asString()).isEqualTo("test-interfaces");
        JsonNode s = k.path("schnittstellen");
        assertThat(s).hasSize(2);
        assertThat(s.get(0).path("name").asString()).isEqualTo("ch.x.Dienst");
        assertThat(s.get(0).path("zweck").asString()).startsWith("Rechnet Preise um.");
        JsonNode m = s.get(0).path("methoden");
        assertThat(m.get(0).path("name").asString()).isEqualTo("umrechnen");
        assertThat(m.get(0).path("rueckgabe").asString()).isEqualTo("long");
        assertThat(m.get(0).path("parameter").get(1).path("name").asString()).isEqualTo("waehrung");
        assertThat(m.get(0).path("zweck").asString()).contains("Wandelt um");
        assertThat(m.get(1).path("art").asString()).isEqualTo("default");
        assertThat(s.get(1).path("name").asString()).isEqualTo("ch.x.Dienst.Teil");
    }

    @Test
    @DisplayName("Ohne Javadoc: Fehler (streng), nur Warnung mit streng=false")
    void zweckPflicht() throws Exception {
        String ohne = """
                package ch.x;
                public interface Dienst { void tu(); }
                """;
        assertThat(kompiliere(ohne, true).getDiagnostics())
                .anyMatch(x -> x.getKind() == Diagnostic.Kind.ERROR && x.getMessage(null).contains("braucht ein Javadoc"));
        assertThat(kompiliere(ohne, false).getDiagnostics()).noneMatch(x -> x.getKind() == Diagnostic.Kind.ERROR);
    }
}

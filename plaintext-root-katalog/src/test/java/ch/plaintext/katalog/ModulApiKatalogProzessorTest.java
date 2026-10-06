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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1422: {@code @ModulApi} und {@code @ModulApiUmsetzung} in einem Fachmodul. Die beiden
 * Annotationen werden hier als Quelltext mitkompiliert (Abbild von plaintext-root-interfaces), weil
 * der Prozessor sie nur über den Namen kennt.
 */
class ModulApiKatalogProzessorTest {

    private static final String MODUL_API = """
            package ch.plaintext.modules;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            public @interface ModulApi {
                Art art();
                Stabilitaet stabilitaet() default Stabilitaet.NEU;
                String seit() default "";
                String ersatz() default "";
                enum Art { SCHNITTSTELLE, DTO }
                enum Stabilitaet { STABIL, NEU, VERALTET }
            }
            """;
    private static final String UMSETZUNG = """
            package ch.plaintext.modules;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface ModulApiUmsetzung {
                String beschreibung();
                Seiteneffekte seiteneffekte();
                String[] hinweise();
                String[] beispiele();
                enum Seiteneffekte { KEINE, INTERN, AUSSEN }
            }
            """;

    @TempDir
    Path tmp;

    private DiagnosticCollector<JavaFileObject> kompiliere(String modul, Map<String, String> quellen) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        List<File> dateien = new ArrayList<>();
        Map<String, String> alle = new java.util.LinkedHashMap<>(quellen);
        alle.put("ch/plaintext/modules/ModulApi.java", MODUL_API);
        alle.put("ch/plaintext/modules/ModulApiUmsetzung.java", UMSETZUNG);
        for (var q : alle.entrySet()) {
            Path f = src.resolve(q.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, q.getValue());
            dateien.add(f.toFile());
        }
        Path out = Files.createDirectories(tmp.resolve("out"));
        JavaCompiler c = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> d = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = c.getStandardFileManager(d, null, null)) {
            var task = c.getTask(null, fm, d, List.of("-d", out.toString(), "-proc:only",
                    "-Aplaintext.katalog.modul=" + modul, "-Aplaintext.katalog.streng=true"), null,
                    fm.getJavaFileObjectsFromFiles(dateien));
            task.setProcessors(List.of(new SchnittstellenKatalogProzessor()));
            task.call();
        }
        return d;
    }

    private JsonNode katalog(String modul) throws Exception {
        return JsonMapper.builderWithJackson2Defaults().build().readTree(tmp.resolve("out/META-INF/plaintext-katalog/" + modul + ".json").toFile());
    }

    @Test
    @DisplayName("Fachmodul: nur @ModulApi-Schnittstellen, mit Art/Stabilität/Herkunft, dazu die beschriebene Umsetzung")
    void fachmodul() throws Exception {
        var d = kompiliere("plaintext-z-fotos", Map.of(
                "ch/x/IFotoQuelle.java", """
                        package ch.x;
                        import ch.plaintext.modules.ModulApi;
                        /** Liefert Fotos aus einer Ablage. */
                        @ModulApi(art = ModulApi.Art.SCHNITTSTELLE, stabilitaet = ModulApi.Stabilitaet.STABIL, seit = "1.749.0")
                        public interface IFotoQuelle { /** Ein Foto. */ byte[] foto(long id); }
                        """,
                "ch/x/Intern.java", """
                        package ch.x;
                        /** Nur innerhalb des Moduls. */
                        public interface Intern { void tu(); }
                        """,
                "ch/x/NasFotoQuelle.java", """
                        package ch.x;
                        import ch.plaintext.modules.ModulApiUmsetzung;
                        import ch.plaintext.modules.ModulApiUmsetzung.Seiteneffekte;
                        @ModulApiUmsetzung(beschreibung = "Liest vom Fotos-Sidecar.", seiteneffekte = Seiteneffekte.KEINE,
                                hinweise = {"Nur eigener Mandant", "Höchstens 20 MB"}, beispiele = {})
                        public class NasFotoQuelle implements IFotoQuelle, Intern {
                            @ModulApiUmsetzung(beschreibung = "Lädt über HTTP/1.1.", seiteneffekte = Seiteneffekte.AUSSEN,
                                    hinweise = {}, beispiele = {"foto(7) -> JPEG"})
                            public byte[] foto(long id) { return new byte[0]; }
                            public void tu() { }
                        }
                        """));
        assertThat(d.getDiagnostics()).noneMatch(x -> x.getKind() == Diagnostic.Kind.ERROR);
        JsonNode k = katalog("plaintext-z-fotos");
        JsonNode s = k.path("schnittstellen");
        assertThat(s).as("Intern ist nicht annotiert und bleibt draussen").hasSize(1);
        assertThat(s.get(0).path("name").asString()).isEqualTo("ch.x.IFotoQuelle");
        assertThat(s.get(0).path("annotiert").asBoolean()).isTrue();
        assertThat(s.get(0).path("art").asString()).isEqualTo("SCHNITTSTELLE");
        assertThat(s.get(0).path("stabilitaet").asString()).isEqualTo("STABIL");
        assertThat(s.get(0).path("seit").asString()).isEqualTo("1.749.0");
        assertThat(s.get(0).path("herkunft").asString()).isEqualTo("im Modul");

        JsonNode u = k.path("umsetzungen");
        assertThat(u).hasSize(1);
        assertThat(u.get(0).path("klasse").asString()).isEqualTo("ch.x.NasFotoQuelle");
        assertThat(u.get(0).path("schnittstellen")).extracting(JsonNode::asText).containsExactly("ch.x.IFotoQuelle", "ch.x.Intern");
        assertThat(u.get(0).path("beschreibung").asString()).isEqualTo("Liest vom Fotos-Sidecar.");
        assertThat(u.get(0).path("seiteneffekte").asString()).isEqualTo("KEINE");
        assertThat(u.get(0).path("hinweise")).extracting(JsonNode::asText).containsExactly("Nur eigener Mandant", "Höchstens 20 MB");
        JsonNode m = u.get(0).path("methoden");
        assertThat(m).hasSize(1);
        assertThat(m.get(0).path("name").asString()).isEqualTo("foto");
        assertThat(m.get(0).path("seiteneffekte").asString()).isEqualTo("AUSSEN");
        assertThat(m.get(0).path("beispiele")).extracting(JsonNode::asText).containsExactly("foto(7) -> JPEG");
    }

    @Test
    @DisplayName("DTO ohne I-Präfix: Fehler; mit I-Präfix und Vorgabe NEU: im Katalog")
    void dtoPraefix() throws Exception {
        var falsch = kompiliere("plaintext-z-zeit", Map.of("ch/x/Zeiteintrag.java", """
                package ch.x;
                import ch.plaintext.modules.ModulApi;
                /** Ein erfasster Zeitraum. */
                @ModulApi(art = ModulApi.Art.DTO)
                public interface Zeiteintrag { long minuten(); }
                """));
        assertThat(falsch.getDiagnostics()).anyMatch(x -> x.getKind() == Diagnostic.Kind.ERROR
                && x.getMessage(null).contains("muss mit I beginnen"));

        var richtig = kompiliere("plaintext-z-zeit2", Map.of("ch/x/IZeiteintrag.java", """
                package ch.x;
                import ch.plaintext.modules.ModulApi;
                /** Ein erfasster Zeitraum. */
                @ModulApi(art = ModulApi.Art.DTO)
                public interface IZeiteintrag { long minuten(); }
                """));
        assertThat(richtig.getDiagnostics()).noneMatch(x -> x.getKind() == Diagnostic.Kind.ERROR);
        JsonNode s = katalog("plaintext-z-zeit2").path("schnittstellen").get(0);
        assertThat(s.path("art").asString()).isEqualTo("DTO");
        assertThat(s.path("stabilitaet").asString()).isEqualTo("NEU");
    }

    @Test
    @DisplayName("Fachmodul ohne @ModulApi: keine Datei; interfaces-Modul: alle öffentlichen Schnittstellen wie bisher")
    void ohneAnnotation() throws Exception {
        kompiliere("plaintext-z-leer", Map.of("ch/x/Intern.java", """
                package ch.x;
                /** Nur innerhalb des Moduls. */
                public interface Intern { void tu(); }
                """));
        assertThat(tmp.resolve("out/META-INF/plaintext-katalog/plaintext-z-leer.json")).doesNotExist();

        kompiliere("plaintext-y-interfaces", Map.of("ch/x/Vertrag.java", """
                package ch.x;
                /** Ein Vertrag ohne Annotation. */
                public interface Vertrag { void tu(); }
                """));
        JsonNode s = katalog("plaintext-y-interfaces").path("schnittstellen");
        assertThat(s).hasSize(1);
        assertThat(s.get(0).path("annotiert").asBoolean()).isFalse();
        assertThat(s.get(0).path("herkunft").asString()).isEqualTo("interfaces-Modul");
    }

    @Test
    @DisplayName("@ModulApiUmsetzung mit leerer Beschreibung: Fehler")
    void umsetzungOhneBeschreibung() throws Exception {
        var d = kompiliere("plaintext-z-x", Map.of("ch/x/Leer.java", """
                package ch.x;
                import ch.plaintext.modules.ModulApiUmsetzung;
                @ModulApiUmsetzung(beschreibung = " ", seiteneffekte = ModulApiUmsetzung.Seiteneffekte.KEINE, hinweise = {}, beispiele = {})
                public class Leer { }
                """));
        assertThat(d.getDiagnostics()).anyMatch(x -> x.getKind() == Diagnostic.Kind.ERROR
                && x.getMessage(null).contains("braucht eine beschreibung"));
    }

    @Test
    @DisplayName("Record als Werte-DTO: im Katalog mit typ=record, ohne I-Präfix erlaubt; Record ohne @ModulApi bleibt draussen")
    void recordDto() throws Exception {
        var d = kompiliere("plaintext-z-mail", Map.of(
                "ch/x/IncomingMail.java", """
                        package ch.x;
                        import ch.plaintext.modules.ModulApi;
                        /** Eine eingegangene Mail. */
                        @ModulApi(art = ModulApi.Art.DTO)
                        public record IncomingMail(String betreff, String von) { }
                        """,
                "ch/x/Intern.java", """
                        package ch.x;
                        /** Nur innen. */
                        public record Intern(int x) { }
                        """));
        assertThat(d.getDiagnostics()).noneMatch(x -> x.getKind() == Diagnostic.Kind.ERROR);
        JsonNode s = katalog("plaintext-z-mail").path("schnittstellen");
        assertThat(s).hasSize(1);
        assertThat(s.get(0).path("kurz").asString()).isEqualTo("IncomingMail");
        assertThat(s.get(0).path("typ").asString()).isEqualTo("record");
        assertThat(s.get(0).path("art").asString()).isEqualTo("DTO");
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.katalog;

import ch.plaintext.sidecars.SidecarRegister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Karte 1422: Nutzer aus den Injektionspunkten, Folgen beim Weglassen, Bauplan. */
class ModulAnalyseTest {

    /** Bezieht den Vertrag direkt: bricht ohne Umsetzer. */
    static class PflichtNutzer {
        PflichtNutzer(SchnittstellenKatalogTest.Vertrag v) {
        }
    }

    /** Bezieht den Vertrag über ObjectProvider: läuft ohne Umsetzer weiter. */
    static class OptionalerNutzer {
        OptionalerNutzer(ObjectProvider<SchnittstellenKatalogTest.Vertrag> v) {
        }
    }

    /** Feld mit @Autowired(required = false): läuft ohne Umsetzer weiter (RechnungMailService.textProvider). */
    static class FeldOptionalNutzer {
        @Autowired(required = false)
        SchnittstellenKatalogTest.Vertrag v;
    }

    /** Gegenprobe: Feld mit @Autowired (Pflicht) bricht ohne Umsetzer. */
    static class FeldPflichtNutzer {
        @Autowired
        SchnittstellenKatalogTest.Vertrag v;
    }

    /** Konstruktor-Parameter mit jspecify-@Nullable (TYPE_USE): optional. */
    static class NullableNutzer {
        NullableNutzer(SchnittstellenKatalogTest.@Nullable Vertrag v) {
        }
    }

    /** Ein MCP-Werkzeug, das der Bauplan finden soll. */
    static class Werkzeuge {
        @McpTool(name = "mahnung_senden", description = "Verschickt eine Mahnung.")
        public String mahnung() {
            return "";
        }
    }

    private AnnotationConfigApplicationContext kontext;
    private ModulAnalyse analyse;

    @BeforeEach
    void kontext() {
        kontext = new AnnotationConfigApplicationContext();
        kontext.registerBean("umsetzung", SchnittstellenKatalogTest.Umsetzung.class);
        kontext.registerBean("pflichtNutzer", PflichtNutzer.class);
        kontext.registerBean("optionalerNutzer", OptionalerNutzer.class);
        kontext.registerBean("werkzeuge", Werkzeuge.class);
        kontext.registerBean("feldOptionalNutzer", FeldOptionalNutzer.class);
        kontext.registerBean("feldPflichtNutzer", FeldPflichtNutzer.class);
        kontext.registerBean("nullableNutzer", NullableNutzer.class);
        kontext.refresh();
        SchnittstellenKatalog katalog = new SchnittstellenKatalog(kontext);
        analyse = new ModulAnalyse(katalog, kontext, kontext.getBeanProvider(SidecarRegister.class));
    }

    @AfterEach
    void zu() {
        kontext.close();
    }

    @Test
    @DisplayName("Nutzer: Pflicht über Konstruktor und @Autowired-Feld, optional über ObjectProvider, @Autowired(required = false) und @Nullable; Gegenprobe: Werkzeuge nutzt nichts")
    void nutzer() {
        List<ModulAnalyse.Nutzung> n = analyse.nutzer("ch.plaintext.modules.katalog.SchnittstellenKatalogTest.Vertrag");
        assertThat(n).extracting(ModulAnalyse.Nutzung::bean, ModulAnalyse.Nutzung::optional).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("pflichtNutzer", false),
                org.assertj.core.groups.Tuple.tuple("optionalerNutzer", true),
                org.assertj.core.groups.Tuple.tuple("feldOptionalNutzer", true),
                org.assertj.core.groups.Tuple.tuple("feldPflichtNutzer", false),
                org.assertj.core.groups.Tuple.tuple("nullableNutzer", true));
        assertThat(analyse.nutzungen()).noneMatch(x -> x.bean().equals("werkzeuge"));
    }

    @Test
    @DisplayName("Injektionspunkte: direkt, ObjectProvider/Optional/List als optional, primitiv ignoriert")
    void bezuege() {
        assertThat(ModulAnalyse.bezug(String.class)).contains(new ModulAnalyse.Bezug("java.lang.String", false));
        assertThat(ModulAnalyse.bezug(int.class)).isEmpty();
        record Halter(ObjectProvider<String> a, Optional<Integer> b, List<Long> c) {
        }
        var k = Halter.class.getDeclaredConstructors()[0].getGenericParameterTypes();
        assertThat(ModulAnalyse.bezug(k[0])).contains(new ModulAnalyse.Bezug("java.lang.String", true));
        assertThat(ModulAnalyse.bezug(k[1])).contains(new ModulAnalyse.Bezug("java.lang.Integer", true));
        assertThat(ModulAnalyse.bezug(k[2])).contains(new ModulAnalyse.Bezug("java.lang.Long", true));
    }

    @Test
    @DisplayName("Weglassen: Pflicht-Nutzer brechen, optionale fallen weg, Nutzer im selben Modul zählen nicht")
    void folgen() {
        var pflicht = new ModulAnalyse.Nutzung("ch.x.IFoto", "a", "ch.y.Galerie", "plaintext-z-galerie", false);
        var optional = new ModulAnalyse.Nutzung("ch.x.IFoto", "b", "ch.y.Wiki", "plaintext-z-wiki", true);
        var selbst = new ModulAnalyse.Nutzung("ch.x.IFoto", "c", "ch.x.Intern", "plaintext-z-fotos", false);
        var mehrere = new ModulAnalyse.Nutzung("ch.x.IMail", "d", "ch.y.Galerie", "plaintext-z-galerie", false);
        List<ModulAnalyse.Folge> f = ModulAnalyse.folgen(List.of(
                new ModulAnalyse.SchnittstellenBild("ch.x.IFoto", "SCHNITTSTELLE", "NEU", "plaintext-z-fotos",
                        List.of("plaintext-z-fotos"), List.of(pflicht, optional, selbst), List.of()),
                new ModulAnalyse.SchnittstellenBild("ch.x.IMail", "SCHNITTSTELLE", "NEU", "m",
                        List.of("plaintext-z-mail", "plaintext-z-mail2"), List.of(mehrere), List.of())));
        assertThat(f).hasSize(1);
        assertThat(f.getFirst().modul()).isEqualTo("plaintext-z-fotos");
        assertThat(f.getFirst().brichtPflicht()).containsExactly("plaintext-z-galerie: ch.y.Galerie → ch.x.IFoto");
        assertThat(f.getFirst().faelltWegOptional()).containsExactly("plaintext-z-wiki: ch.y.Wiki → ch.x.IFoto");
    }

    @Test
    @DisplayName("Bauplan: Schnittstelle und Werkzeug gefunden, unbekannter Begriff als Lücke")
    void bauplan() {
        assertThat(ModulAnalyse.begriffe("Mahnung per Messenger verschicken")).containsExactly("mahnung", "messenger", "verschicken");
        ModulAnalyse.Bauplan b = analyse.bauplan("Mahnung rechnet Quarkbrot");
        assertThat(b.schnittstellen()).extracting(ModulAnalyse.SchnittstellenBild::name)
                .contains("ch.plaintext.modules.katalog.SchnittstellenKatalogTest.Vertrag");
        assertThat(b.werkzeuge()).extracting(ModulAnalyse.Werkzeug::name).containsExactly("mahnung_senden");
        assertThat(b.luecken()).containsExactly("quarkbrot");
        assertThat(b.faehigkeiten()).isEmpty();
    }

    @Test
    @DisplayName("Gesamtbild: Vertrag hat Umsetzer und Nutzer, DTO ohne Nutzer steht nicht unter 'ohne Umsetzer'")
    void gesamt() {
        ModulAnalyse.Analyse a = analyse.analysiere();
        ModulAnalyse.SchnittstellenBild v = a.schnittstellen().stream()
                .filter(s -> s.name().endsWith("SchnittstellenKatalogTest.Vertrag")).findFirst().orElseThrow();
        assertThat(v.umgesetztIn()).isNotEmpty();
        assertThat(v.nutzer()).hasSize(5);
        assertThat(a.ohneUmsetzer()).doesNotContain("ch.plaintext.modules.katalog.IZeiteintrag", "ch.plaintext.fehlt.Weg");
        assertThat(a.module()).isNotEmpty();
    }

    @Test
    @DisplayName("Karte 1438: Modulbild für die Modulseite vereint das Modul und sein -interfaces-Jar; Gegenprobe Namensanfang")
    void bildVon() {
        ModulAnalyse.ModulBild b = analyse.bildVon("test");
        assertThat(b.modul()).isEqualTo("test");
        assertThat(b.bietetAn()).contains("ch.plaintext.modules.katalog.SchnittstellenKatalogTest.Vertrag");
        assertThat(analyse.bildVon("tes").bietetAn()).as("kein Präfix-Treffer auf test-interfaces").isEmpty();
        assertThat(analyse.bildVon("gibtesnicht").setztUm()).isEmpty();
        assertThat(new ModulAnalyse.ModulBild("plaintext-admin-cron", List.of(), List.of(), List.of()).ebene()).isEqualTo("root");
        assertThat(new ModulAnalyse.Nutzung("x", "b", "k", "plaintext-z-wiki", false).ebene()).isEqualTo("modul");
    }
}
